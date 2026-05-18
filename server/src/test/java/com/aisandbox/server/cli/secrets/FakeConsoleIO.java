package com.aisandbox.server.cli.secrets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Scripted fake for {@link ConsoleIO} used across the UC06
 * {@code secrets seed} test suite. Captures everything written through
 * {@link #print(String)} / {@link #println(String)} into
 * {@link #printed} and pops scripted answers off {@link #inputLines}
 * for {@link #readLine()} and {@link #passwords} for
 * {@link #readPassword()}.
 *
 * <p>{@link #tty} controls the AC11/AC12 hinge — tests flip it to
 * {@code false} to assert the no-TTY fail-fast behaviour.
 */
public final class FakeConsoleIO implements ConsoleIO {

    public boolean tty = true;
    public final Deque<String> inputLines = new ArrayDeque<>();
    public final Deque<char[]> passwords = new ArrayDeque<>();
    public final List<String> printed = new ArrayList<>();

    @Override
    public boolean hasTty() {
        return tty;
    }

    @Override
    public void println(String line) {
        printed.add(line);
    }

    @Override
    public void print(String fragment) {
        // print() doesn't append a newline in the real impl; we capture
        // it as a distinct line anyway so assertions can match prompt
        // labels verbatim.
        printed.add(fragment);
    }

    @Override
    public String readLine() {
        return inputLines.pollFirst();
    }

    @Override
    public char[] readPassword() {
        return passwords.pollFirst();
    }

    /** Concatenated record of every fragment written — for substring assertions. */
    public String allOutput() {
        return String.join("\n", printed);
    }
}
