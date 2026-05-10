#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
YELLOW=$'\033[1;33m'
RED=$'\033[1;31m'
MAGENTA=$'\033[1;35m'
BOLD=$'\033[1m'
RESET=$'\033[0m'

clear_screen() { printf "\033[H\033[2J"; }

clear_screen
printf "%s%s=== Clean environment ===%s\n\n" "$BOLD" "$RED" "$RESET"
printf "  This will %sPERMANENTLY DELETE%s the following:\n\n" "$RED" "$RESET"
printf "    %ssecrets/git-key%s, %ssecrets/gh-token%s, %ssecrets/gitconfig%s\n" "$YELLOW" "$RESET" "$YELLOW" "$RESET" "$YELLOW" "$RESET"
printf "      (the copies inside this project — your originals in ~/.ssh and your\n"
printf "       host gh login are untouched)\n\n"
printf "    %sclaude-config/*%s\n" "$YELLOW" "$RESET"
printf "      (you'll have to /login and re-accept the trust + bypass dialogs)\n\n"
printf "    %sworkspace/*%s\n" "$YELLOW" "$RESET"
printf "      (ALL FILES — cloned projects, %suncommitted local work%s, everything)\n\n" \
    "$RED" "$RESET"
printf "    %sai-context:latest%s docker image\n" "$YELLOW" "$RESET"
printf "      (the next ./setup.sh will rebuild it from scratch — slower)\n\n"
printf "  It will also stop and remove the running container.\n\n"
printf "  After this, %s./setup.sh%s starts everything fresh.\n\n" "$MAGENTA" "$RESET"
printf "  %sType 'yes' to confirm (anything else cancels):%s " "$BOLD" "$RESET"
read -r confirm
if [ "$confirm" != "yes" ]; then
    printf "\n  Cancelled. Nothing was changed.\n"
    exit 0
fi

remove_contents() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    for item in "$dir"/* "$dir"/.[!.]*; do
        [ -e "$item" ] || continue
        case "$(basename "$item")" in
            .gitkeep) ;;
            *) rm -rf "$item" ;;
        esac
    done
}

echo
echo "  Stopping container and removing image..."
docker compose down --rmi all 2>/dev/null || true

echo "  Removing copied secrets..."
rm -f secrets/git-key secrets/gh-token secrets/gitconfig

echo "  Removing Claude config..."
remove_contents claude-config

echo "  Removing workspace contents..."
remove_contents workspace

echo
printf "  %s✓ Clean. Run ./setup.sh to start fresh.%s\n" "$GREEN" "$RESET"
