package com.purposegate

import java.util.UUID
import java.util.concurrent.TimeUnit

/** Root owns the installed script. The app supplies no writable runtime dependencies. */
internal object RootKeepAlive {
    const val SCRIPT_VERSION = 2
    private const val SCRIPT = "/data/adb/service.d/purposegate-keepalive.sh"
    private const val SERVICE = "com.purposegate/com.purposegate.PurposeAccessibilityService"

    fun configure(enabled: Boolean): Boolean = runCatching {
        val command = if (enabled) enableCommand() else
            "test \"\$(id -u)\" = 0 && rm -f '$SCRIPT'"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            process.waitFor(45, TimeUnit.SECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }.getOrDefault(false)

    private fun enableCommand(): String {
        val generation = UUID.randomUUID().toString()
        // Use a fresh marker so a previously stopped worker cannot resume after a quick re-enable.
        return """
            set -e
            test "§(id -u)" = 0
            test -d /data/adb
            command -v nohup >/dev/null
            user=§(am get-current-user)
            case "§user" in ''|*[!0-9]*) exit 1 ;; esac
            umask 077
            mkdir -p /data/adb/service.d
            tmp=§(mktemp /data/adb/service.d/.purposegate.XXXXXX)
            trap 'rm -f "§tmp"' EXIT
            printf '#!/system/bin/sh\n# $generation\ntarget_user=%s\n' "§user" > "§tmp"
            cat >> "§tmp" <<'PURPOSEGATE_DAEMON'
            PATH=/system/bin:/system/xbin
            script='$SCRIPT'
            service='$SERVICE'
            if [ "§1" != --worker ]; then
                nohup /system/bin/sh "§script" --worker </dev/null >/dev/null 2>&1 &
                exit 0
            fi
            active() {
                test "§(sed -n '2p' "§script" 2>/dev/null)" = '# $generation'
            }
            until [ "§(getprop sys.boot_completed)" = 1 ] &&
                [ "§(am get-started-user-state "§target_user" 2>/dev/null)" = RUNNING_UNLOCKED ]; do
                active || exit 0
                sleep 5
            done
            sleep 180
            while active; do
                if [ "§(getprop sys.boot_completed)" = 1 ] &&
                    [ "§(am get-current-user 2>/dev/null)" = "§target_user" ] &&
                    [ "§(am get-started-user-state "§target_user" 2>/dev/null)" = RUNNING_UNLOCKED ] &&
                    pm path --user "§target_user" com.purposegate >/dev/null 2>&1; then
                    if enabled=§(settings --user "§target_user" get secure enabled_accessibility_services); then
                        [ "§enabled" = null ] && enabled=''
                        case ":§enabled:" in
                            *":§service:"*) ;;
                            *)
                                if active; then
                                    settings --user "§target_user" put secure enabled_accessibility_services "§{enabled:+§enabled:}§service"
                                fi
                                ;;
                        esac
                    fi
                fi
                sleep 180
            done
            PURPOSEGATE_DAEMON
            chmod 700 "§tmp"
            mv -f "§tmp" '$SCRIPT'
            /system/bin/sh '$SCRIPT'
        """.trimIndent().replace('§', '$')
    }
}
