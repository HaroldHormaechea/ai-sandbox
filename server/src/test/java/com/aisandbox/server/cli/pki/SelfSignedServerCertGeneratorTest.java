package com.aisandbox.server.cli.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC07 Bug A — {@link SelfSignedServerCertGenerator} produces a self-
 * signed server cert with a SubjectAlternativeName extension whose
 * contents exactly mirror the caller-supplied entries (no implicit
 * filtering, no implicit dedup — the composer in
 * {@code PkiInitCommand.composeSanEntries} owns dedup + canonicalisation,
 * see its Javadoc for the contract).
 *
 * <p>The verification uses JDK-native
 * {@link X509Certificate#getSubjectAlternativeNames()} rather than
 * Bouncy Castle's {@code Extension.subjectAlternativeName} parser so the
 * test is decoupled from BC versions: the JDK API returns a
 * {@code Collection<List<?>>} where each entry is
 * {@code [Integer type, Object value]} per RFC 5280 § 4.2.1.6 — type 2
 * for {@code dNSName}, type 7 for {@code iPAddress}.
 *
 * <p>Layer note: case-insensitive dedup (e.g. {@code DNS:Foo} +
 * {@code DNS:foo} collapsing to one) is the {@code composeSanEntries}
 * caller's job; covered in {@code PkiInitCommandTest}. This test pins
 * the generator's narrower contract: pass-through and validation.
 */
class SelfSignedServerCertGeneratorTest {

    private static final int GN_TYPE_DNS = 2;
    private static final int GN_TYPE_IP = 7;

    @Test
    void empty_san_list_emits_no_san_extension() throws Exception {
        // Back-compat: the single-arg overload mints a cert with no SAN
        // block, matching pre-UC07 behaviour. Two-arg with an empty list
        // MUST behave identically — the generator skips the extension
        // when the list is empty rather than emitting an empty SAN.
        var matZeroArg = new SelfSignedServerCertGenerator().generate("server-cn");
        assertThat(matZeroArg.certificate().getSubjectAlternativeNames())
                .as("single-arg generate(String) MUST mint a cert with no SAN extension")
                .isNull();

        var matEmptyList = new SelfSignedServerCertGenerator().generate("server-cn", List.of());
        assertThat(matEmptyList.certificate().getSubjectAlternativeNames())
                .as("two-arg generate(cn, []) MUST also produce no SAN extension")
                .isNull();
    }

    @Test
    void explicit_dns_and_ip_entries_land_in_extension() throws Exception {
        // The shape the operator expects: --san DNS:foo.example.com,IP:10.0.0.5
        // round-trips through the generator and back through the JDK's
        // X509 parser. Order is preserved, types are correctly tagged
        // (dNSName = 2, iPAddress = 7).
        List<String> entries = List.of("DNS:foo.example.com", "IP:10.0.0.5");
        var mat = new SelfSignedServerCertGenerator().generate("server-cn", entries);
        Collection<List<?>> san = mat.certificate().getSubjectAlternativeNames();
        assertThat(san).isNotNull();

        List<String> dns = collect(san, GN_TYPE_DNS);
        List<String> ip = collect(san, GN_TYPE_IP);
        assertThat(dns).containsExactly("foo.example.com");
        assertThat(ip).containsExactly("10.0.0.5");
    }

    @Test
    void generator_does_not_dedup_case_different_entries() throws Exception {
        // Contract pin: the generator is a thin shim — it does NOT
        // case-fold or dedup. That responsibility lives in
        // PkiInitCommand.composeSanEntries (the only production caller),
        // whose Javadoc documents the policy: tag uppercased, value
        // lowercased, dedup by canonical form. Drift would break the
        // single-writer assumption — if either side started deduping,
        // we'd risk silently dropping operator-supplied entries the
        // composer intended to pass through verbatim.
        //
        // If a future refactor moves dedup into the generator, this
        // test should flip to assert ONE entry; the composer's dedup
        // would then become redundant and could be removed. Either way,
        // this test pins which side owns the contract.
        List<String> entries = List.of("DNS:Foo.example.com", "DNS:foo.example.com");
        var mat = new SelfSignedServerCertGenerator().generate("server-cn", entries);
        List<String> dns = collect(mat.certificate().getSubjectAlternativeNames(), GN_TYPE_DNS);
        // dNSName values in X.509 are case-sensitive on the wire, but
        // JDK lowercases them on parse — both inputs come back lower.
        // The duplicate IS still there: two list elements, both
        // "foo.example.com". This is what we assert; dedup is upstream.
        assertThat(dns).hasSize(2);
        assertThat(dns).allMatch(s -> s.equalsIgnoreCase("foo.example.com"));
    }

    @Test
    void malformed_entries_throw_illegal_argument() {
        // Each branch of parseSanEntries' validation must raise IAE so
        // the CLI surfaces a useful error to the operator (vs. silently
        // dropping bad input). The four canonical malformed shapes:
        //
        //   1. Empty value:      "DNS:"
        //   2. Unknown tag:      "FOO:bar"
        //   3. Null in list:     (List.of disallows null, so use Arrays.asList(null))
        //   4. Empty string:     ""
        //
        // No-colon entries also throw — pinned as a fifth case below.

        // 1. Empty value.
        assertThatThrownBy(() -> new SelfSignedServerCertGenerator().generate("server-cn", List.of("DNS:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty value")
                .hasMessageContaining("DNS:");

        // 2. Unknown tag.
        assertThatThrownBy(() -> new SelfSignedServerCertGenerator().generate("server-cn", List.of("FOO:bar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SAN tag")
                .hasMessageContaining("FOO");

        // 3. Null in the list. List.of(...) rejects null arguments at
        //    build time, so we use Arrays.asList which allows nulls.
        List<String> withNull = Arrays.asList((String) null);
        assertThatThrownBy(() -> new SelfSignedServerCertGenerator().generate("server-cn", withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        // 4. Empty string.
        assertThatThrownBy(() -> new SelfSignedServerCertGenerator().generate("server-cn", List.of("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        // 5. No colon at all — bonus assertion, pins the "missing
        //    delimiter" error path the parser exposes.
        assertThatThrownBy(() -> new SelfSignedServerCertGenerator().generate("server-cn", List.of("foo.example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed SAN entry");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<String> collect(Collection<List<?>> san, int generalNameType) {
        List<String> out = new ArrayList<>();
        if (san == null) {
            return out;
        }
        for (List<?> entry : san) {
            int type = (Integer) entry.get(0);
            if (type == generalNameType) {
                out.add(String.valueOf(entry.get(1)));
            }
        }
        return out;
    }
}
