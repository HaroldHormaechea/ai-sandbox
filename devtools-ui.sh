# shellcheck shell=bash
# devtools-ui.sh — pure-shell raw-mode cursor checkbox selector for the
# ai-sandbox devtools capabilities (UC-27).
#
# A capability per line, prefixed [X]/[ ], a highlighted cursor line, arrow keys
# AND mouse-wheel scroll to move, Space to toggle, Enter to commit, q/Esc to
# cancel. Pure shell (`read -rsn1` + ANSI) — no whiptail/dialog, so the .deb TTY
# auto-onboard path keeps working (AC#1). Dependency-aware: selecting a
# capability auto-selects its transitive DEPENDS_ON (visibly marked), and
# deselecting a depended-on capability prompts and cascade-deselects only on
# confirmation (AC#5).
#
# Sourced by:
#   - setup.sh (Step 6 of the wizard, and the --reconfigure short-circuit)
#   - devtools-select.sh (the standalone entry the Java install-time CLI shells
#     out to, so the .deb onboard reaches the identical selector)
# REQUIRES lib.sh to have been sourced first (catalog + resolver functions).
#
# No top-level side effects: this file only defines functions. The public entry
# point is `devtools_run_selector`. It honours $AISB_DEVTOOLS_FILE for the ledger
# location (the Java CLI / install mode set it to the session/host path).
#
# Designed to be safe under `set -euo pipefail` (setup.sh inherits it): every
# read is guarded, no bare arithmetic that can evaluate to 0, and the key reader
# always returns 0.

# ── TTY detection ────────────────────────────────────────────────────────────
# Refuse cleanly when stdin or stdout is not a terminal (piped / headless). The
# caller maps a non-zero return to DEFERRED — identical to the .deb headless
# fallback (AC#14). An explicit override (AISB_DEVTOOLS_ASSUME_TTY=1) exists for
# automated tests only; never set it in production paths.
_dtui_is_tty() {
    [ "${AISB_DEVTOOLS_ASSUME_TTY:-0}" = "1" ] && return 0
    [ -t 0 ] && [ -t 1 ]
}

# ── Terminal mode management ─────────────────────────────────────────────────
_DTUI_STTY_SAVE=""
_dtui_term_setup() {
    _DTUI_STTY_SAVE="$(stty -g 2>/dev/null || true)"
    stty -echo -icanon min 1 time 0 2>/dev/null || true
    printf '\033[?25l'                 # hide cursor
    printf '\033[?7l'                  # disable auto-wrap: a row wider than the
                                       # terminal is clipped at the margin instead
                                       # of spilling onto a 2nd physical row, so
                                       # the in-place redraw's "1 logical line ==
                                       # 1 physical row" cursor-up math stays exact
                                       # at any width (else long rows duplicate).
    printf '\033[?1000h\033[?1006h'    # enable mouse (button + SGR) → wheel events
}
# Restore on EVERY exit path (commit, cancel, EOF, Ctrl-C, error). Idempotent.
_dtui_term_restore() {
    printf '\033[?1006l\033[?1000l'    # disable mouse
    printf '\033[?7h'                  # restore auto-wrap (paired with ?7l in setup)
    printf '\033[?25h'                 # show cursor
    if [ -n "${_DTUI_STTY_SAVE:-}" ]; then
        stty "$_DTUI_STTY_SAVE" 2>/dev/null || stty sane 2>/dev/null || true
    else
        stty sane 2>/dev/null || true
    fi
}

# ── Key decoding ─────────────────────────────────────────────────────────────
# Reads one logical key and echoes an action token: up|down|toggle|commit|
# cancel|eof|noop. Decodes arrow keys (\e[A/\e[B), vim j/k, and SGR mouse-wheel
# (\e[<64;… = up, \e[<65;… = down). A bare Esc cancels. Always returns 0.
_dtui_read_key() {
    local c c2 c3 ch seq cb
    if ! IFS= read -rsn1 c; then printf 'eof'; return 0; fi
    case "$c" in
        ''|$'\n'|$'\r') printf 'commit'; return 0 ;;
        ' ')            printf 'toggle'; return 0 ;;
        q|Q)            printf 'cancel'; return 0 ;;
        j|J)            printf 'down';   return 0 ;;
        k|K)            printf 'up';     return 0 ;;
        $'\033')
            # Esc: arrow / mouse / bare Esc. Short timeout distinguishes a lone
            # Esc keypress from the start of an escape sequence.
            if ! IFS= read -rsn1 -t 0.02 c2; then printf 'cancel'; return 0; fi
            [ "$c2" = '[' ] || { printf 'cancel'; return 0; }
            if ! IFS= read -rsn1 -t 0.02 c3; then printf 'noop'; return 0; fi
            case "$c3" in
                A) printf 'up';   return 0 ;;
                B) printf 'down'; return 0 ;;
                '<')
                    # SGR mouse: read Cb;Cx;Cy up to the trailing M/m.
                    seq=""
                    while IFS= read -rsn1 -t 0.05 ch; do
                        case "$ch" in M|m) break ;; esac
                        seq="$seq$ch"
                    done
                    cb="${seq%%;*}"
                    case "$cb" in
                        64) printf 'up';   return 0 ;;   # wheel up
                        65) printf 'down'; return 0 ;;   # wheel down
                        *)  printf 'noop'; return 0 ;;
                    esac
                    ;;
                *) printf 'noop'; return 0 ;;
            esac
            ;;
        *) printf 'noop'; return 0 ;;
    esac
}

# ── Selection state (parallel arrays, indexed alongside _DTUI_IDS) ───────────
_DTUI_IDS=()
_DTUI_SEL=()      # 0/1 selected
_DTUI_AUTO=()     # 0/1 auto-selected as a dependency (vs. explicitly chosen)
_DTUI_AVAIL=()    # 0/1 available on this host's arch
_DTUI_CURSOR=0
_DTUI_RENDERED=0
_DTUI_FLASH=""

# _dtui_index_of ID → echo array index, or -1; returns non-zero if absent.
_dtui_index_of() {
    local want="$1" i
    for i in "${!_DTUI_IDS[@]}"; do
        if [ "${_DTUI_IDS[$i]}" = "$want" ]; then printf '%s' "$i"; return 0; fi
    done
    printf '%s' "-1"
    return 1
}

# _dtui_select i → mark i explicitly selected and auto-select its available
# transitive dependencies (AC#5).
_dtui_select() {
    local i="$1" dep di
    _DTUI_SEL[$i]=1
    _DTUI_AUTO[$i]=0
    while IFS= read -r dep; do
        [ -n "$dep" ] || continue
        di="$(_dtui_index_of "$dep")" || continue
        [ "$di" -ge 0 ] || continue
        if [ "${_DTUI_SEL[$di]}" != "1" ]; then
            _DTUI_SEL[$di]=1
            _DTUI_AUTO[$di]=1
        fi
    done < <(devtool_deps_transitive "${_DTUI_IDS[$i]}")
}

# _dtui_selected_dependents i → echo currently-selected ids that transitively
# depend on i (the cascade set), one per line.
_dtui_selected_dependents() {
    local i="$1" sel_ids=() j target
    target="${_DTUI_IDS[$i]}"
    for j in "${!_DTUI_IDS[@]}"; do
        [ "${_DTUI_SEL[$j]}" = "1" ] && sel_ids+=("${_DTUI_IDS[$j]}")
    done
    devtool_dependents_among "$target" ${sel_ids[@]+"${sel_ids[@]}"}
}

# _dtui_deselect i → clear i and cascade-clear any selected dependents.
_dtui_deselect() {
    local i="$1" dep di
    while IFS= read -r dep; do
        [ -n "$dep" ] || continue
        di="$(_dtui_index_of "$dep")" || continue
        [ "$di" -ge 0 ] && { _DTUI_SEL[$di]=0; _DTUI_AUTO[$di]=0; }
    done < <(_dtui_selected_dependents "$i")
    _DTUI_SEL[$i]=0
    _DTUI_AUTO[$i]=0
}

# _dtui_confirm MSG → print a transient yes/no prompt below the frame, read one
# key, clear the prompt line. Returns 0 only on y/Y. Leaves the cursor at the
# same position the frame's trailing newline left it, so the next render's
# cursor-up count stays correct (no artifacts).
_dtui_confirm() {
    local msg="$1" key=""
    printf '  \033[1;33m%s\033[0m\033[K' "$msg"
    IFS= read -rsn1 -t 30 key || key=""
    printf '\r\033[K'
    case "$key" in [yY]) return 0 ;; *) return 1 ;; esac
}

# _dtui_do_toggle → apply Space at the cursor: refuse unavailable rows; on
# deselect of a depended-on capability, confirm + cascade; else toggle.
_dtui_do_toggle() {
    local i="$_DTUI_CURSOR" deps list label
    if [ "${_DTUI_AVAIL[$i]}" != "1" ]; then
        _DTUI_FLASH="$(devtool_label "${_DTUI_IDS[$i]}") is unavailable on this $(aisb_host_arch) host."
        return 0
    fi
    label="$(devtool_label "${_DTUI_IDS[$i]}")"
    if [ "${_DTUI_SEL[$i]}" = "1" ]; then
        deps="$(_dtui_selected_dependents "$i")"
        if [ -n "$deps" ]; then
            list="$(printf '%s' "$deps" | tr '\n' ' ')"
            if _dtui_confirm "Disabling this will also disable: ${list%% }. Continue? [y/N]"; then
                _dtui_deselect "$i"
                _DTUI_FLASH="Disabled $label (and dependents: ${list%% })."
            else
                _DTUI_FLASH="Kept $label enabled."
            fi
        else
            _dtui_deselect "$i"
            _DTUI_FLASH="Disabled $label."
        fi
    else
        _dtui_select "$i"
        _DTUI_FLASH="Enabled $label."
    fi
}

# ── Render ───────────────────────────────────────────────────────────────────
# Redraws the frame in place: moves up over the previous frame, reprints each
# row (reverse-video on the cursor line, dim on unavailable rows), a help line,
# and a single flash line (always printed so the line count is stable).
_dtui_render() {
    local i id mark label note arch
    arch="$(aisb_host_arch)"
    if [ "${_DTUI_RENDERED:-0}" -gt 0 ]; then
        printf '\033[%dA' "$_DTUI_RENDERED"
    fi
    local n=0
    for i in "${!_DTUI_IDS[@]}"; do
        id="${_DTUI_IDS[$i]}"
        label="$(devtool_label "$id")"
        if [ "${_DTUI_AVAIL[$i]}" != "1" ]; then
            local uline="  [-] $label  (amd64-only — unavailable on $arch)"
            if [ "$i" = "$_DTUI_CURSOR" ]; then
                printf '\033[7m\033[2m%s\033[0m\033[K\n' "$uline"
            else
                printf '\033[2m%s\033[0m\033[K\n' "$uline"
            fi
        else
            mark=" "; [ "${_DTUI_SEL[$i]}" = "1" ] && mark="X"
            note=""
            [ "${_DTUI_AUTO[$i]}" = "1" ] && note="  \033[36m(required by another selection)\033[0m"
            if [ "$i" = "$_DTUI_CURSOR" ]; then
                printf '\033[7m  [%s] %s\033[0m%b\033[K\n' "$mark" "$label" "$note"
            else
                printf '  [%s] %s%b\033[K\n' "$mark" "$label" "$note"
            fi
        fi
        n=$((n + 1))
    done
    printf '\033[K\n'; n=$((n + 1))
    printf '  \033[1m↑/↓\033[0m or scroll move   \033[1mSpace\033[0m toggle   \033[1mEnter\033[0m commit   \033[1mq\033[0m/Esc cancel\033[K\n'
    n=$((n + 1))
    printf '  \033[33m%s\033[0m\033[K\n' "${_DTUI_FLASH}"
    n=$((n + 1))
    _DTUI_RENDERED=$n
}

# ── State init ───────────────────────────────────────────────────────────────
# Build the parallel arrays from the catalog + current ledger. Pre-enabled rows
# that are unavailable on this arch are forced off; dependencies of pre-enabled
# rows are re-marked auto so the pre-fill is internally consistent (AC#5,#6).
_dtui_init_state() {
    _DTUI_IDS=(); _DTUI_SEL=(); _DTUI_AUTO=(); _DTUI_AVAIL=()
    local id
    while IFS= read -r id; do
        [ -n "$id" ] || continue
        _DTUI_IDS+=("$id")
        if devtool_is_enabled "$id"; then _DTUI_SEL+=(1); else _DTUI_SEL+=(0); fi
        _DTUI_AUTO+=(0)
        if devtool_is_available "$id"; then _DTUI_AVAIL+=(1); else _DTUI_AVAIL+=(0); fi
    done < <(devtool_catalog_ids)
    if [ "${#_DTUI_IDS[@]}" -eq 0 ]; then
        warn "No development-tool capabilities are registered under $AISB_DEVTOOLS_DIR."
        return 1
    fi
    local i
    for i in "${!_DTUI_IDS[@]}"; do
        [ "${_DTUI_AVAIL[$i]}" != "1" ] && _DTUI_SEL[$i]=0
    done
    for i in "${!_DTUI_IDS[@]}"; do
        [ "${_DTUI_SEL[$i]}" = "1" ] && _dtui_select "$i"
    done
    _DTUI_CURSOR=0
    _DTUI_RENDERED=0
    _DTUI_FLASH=""
}

# _dtui_commit → write the ledger from the final selection. write_enabled_devtools
# normalizes to byte-stable catalog order regardless of selection sequence.
_dtui_commit() {
    local final=() i
    for i in "${!_DTUI_IDS[@]}"; do
        [ "${_DTUI_SEL[$i]}" = "1" ] && final+=("${_DTUI_IDS[$i]}")
    done
    write_enabled_devtools ${final[@]+"${final[@]}"}
    printf '\n'
    if [ "${#final[@]}" -eq 0 ]; then
        ok "Development tools: none enabled (sessions remain identical to the default)."
    else
        ok "Development tools persisted: ${final[*]}"
        info "Changes apply to NEW sessions on the next ./spawn.sh — existing sessions are unaffected."
        info "Recycle a session via ./clean.sh + ./spawn.sh to retrofit it."
    fi
}

# ── Public entry point ───────────────────────────────────────────────────────
# devtools_run_selector → render the interactive selector, persist on commit.
# Returns 0 on commit, 130 on cancel/EOF, 3 when there is no TTY (DEFERRED).
# Restores the terminal on every exit path.
devtools_run_selector() {
    if ! _dtui_is_tty; then
        warn "The development-tools selector needs an interactive terminal." >&2
        warn "Re-run from a TTY: ./setup.sh --reconfigure" >&2
        return 3
    fi
    _dtui_init_state || return 1

    printf '  Select the development tools to provision into the sessions you spawn.\n'
    printf '  Each capability is installed eagerly at spawn; unselected ones leave no trace.\n\n'

    _dtui_term_setup
    trap '_dtui_term_restore; exit 130' INT TERM

    local action rc=0
    while true; do
        _dtui_render
        _DTUI_FLASH=""
        action="$(_dtui_read_key)"
        case "$action" in
            up)     [ "$_DTUI_CURSOR" -gt 0 ] && _DTUI_CURSOR=$((_DTUI_CURSOR - 1)) ;;
            down)   [ "$_DTUI_CURSOR" -lt $(( ${#_DTUI_IDS[@]} - 1 )) ] && _DTUI_CURSOR=$((_DTUI_CURSOR + 1)) ;;
            toggle) _dtui_do_toggle ;;
            commit) rc=0;   break ;;
            cancel) rc=130; break ;;
            eof)    rc=130; break ;;
            *)      : ;;
        esac
    done

    _dtui_term_restore
    trap - INT TERM

    if [ "$rc" -eq 0 ]; then
        _dtui_commit
    else
        printf '\n'
        warn "Selector cancelled — selection left unchanged."
    fi
    return "$rc"
}
