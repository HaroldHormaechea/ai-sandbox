package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.service.DockerEnumerationService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC24 — enumeration is several {@code docker compose} calls; this test
 * covers both supported output shapes (JSON array, NDJSON) and the title
 * normalisation cases. NDJSON support is on the list of "developer
 * known limitations" the team-lead flagged for QA validation.
 */
class DockerEnumerationServiceTest {

    @Test
    void parses_json_array_output() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        String arrayJson =
                """
                [{"Name":"ai-sandbox-1","Status":"running"},
                 {"Name":"ai-sandbox-3","Status":"running"},
                 {"Name":"unrelated-project","Status":"running"}]
                """;
        // First call: compose ls
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, arrayJson, ""));
        // Container id lookups + label + tmux title — return non-empty so we go through happy path.
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "abc123\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "my-label", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "claude", ""));

        DockerEnumerationService svc = new DockerEnumerationService(exec);
        List<SessionRecord> got = svc.enumerate();

        assertThat(got).hasSize(2);
        assertThat(got).extracting(SessionRecord::n).containsExactly(1, 3);
        assertThat(got).allSatisfy(r -> assertThat(r.label()).isEqualTo("my-label"));
        // 'claude' is normalised to '(idle)'.
        assertThat(got).allSatisfy(r -> assertThat(r.tmuxTitle()).isEqualTo("(idle)"));
        assertThat(got).allSatisfy(r -> assertThat(r.state()).isEqualTo("running"));
    }

    @Test
    void parses_ndjson_output() throws Exception {
        // KNOWN LIMITATION (developer flagged): NDJSON path is best-effort.
        // This test fails today if the NDJSON branch is broken.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        String ndjson =
                """
                {"Name":"ai-sandbox-2","Status":"running"}
                {"Name":"ai-sandbox-5","Status":"running"}
                """;
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, ndjson, ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        List<SessionRecord> got = new DockerEnumerationService(exec).enumerate();

        assertThat(got).extracting(SessionRecord::n).containsExactly(2, 5);
        assertThat(got).extracting(SessionRecord::tmuxTitle).containsExactly("doing-thing", "doing-thing");
    }

    @Test
    void returns_empty_when_compose_ls_fails() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "boom"));

        assertThat(new DockerEnumerationService(exec).enumerate()).isEmpty();
    }

    @Test
    void normalises_idle_titles() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-7\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "<no value>", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "bash", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.label()).isEqualTo("");
        assertThat(r.tmuxTitle()).isEqualTo("(idle)");
    }

    @Test
    void title_unavailable_when_container_id_missing() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-9\"}]", ""));
        // ps returns empty → no container id.
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "\n", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        assertThat(r.state()).isEqualTo("exited");
    }
}
