package com.aisandbox.server.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root of the {@code aisandboxctl} CLI. Subcommands handle PKI init,
 * secret seeding, client mint, client revoke, and client list. Spring
 * is intentionally not loaded — the CLI is meant to run on operator
 * workstations where the full server context would be wasteful.
 */
@Command(
        name = "aisandboxctl",
        mixinStandardHelpOptions = true,
        version = "0.0.1",
        description = "ai-sandbox PKI and allowlist helper.",
        subcommands = {
            CommandLine.HelpCommand.class,
            PkiInitCommand.class,
            SecretsSeedCommand.class,
            OnboardCommand.class,
            ReconfigureCommand.class,
            ClientMintCommand.class,
            ClientRevokeCommand.class,
            ClientListCommand.class
        })
public class AisandboxctlCommand implements Runnable {

    @Override
    public void run() {
        // No-op; the CommandLine prints help when run bare.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new AisandboxctlCommand()).execute(args);
        System.exit(exit);
    }
}
