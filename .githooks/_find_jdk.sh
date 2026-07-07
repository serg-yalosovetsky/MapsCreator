#!/bin/sh
# Shared helper: locate a Gradle-8.6-compatible JDK (17..21).
# The machine default `java` may be too new (e.g. JDK 23), which Gradle 8.6 rejects.
# Prints a JAVA_HOME path on stdout and returns 0, or returns 1 if none found.
find_jdk() {
    # 1) honour an already-good JAVA_HOME
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" -o -x "$JAVA_HOME/bin/java.exe" ]; then
        case "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)" in
            *\"1[789].* | *\"2[01].*) echo "$JAVA_HOME"; return 0 ;;
        esac
    fi
    # 2) common install locations (Windows + Android Studio JBR)
    for pat in \
        "$LOCALAPPDATA/Programs/Microsoft/jdk-17"* \
        "$LOCALAPPDATA/Programs/Microsoft/jdk-21"* \
        "/c/Program Files/Microsoft/jdk-17"* \
        "/c/Program Files/Microsoft/jdk-21"* \
        "/c/Program Files/Eclipse Adoptium/jdk-17"* \
        "/c/Program Files/Android/Android Studio/jbr"; do
        for cand in $pat; do
            if [ -x "$cand/bin/java" ] || [ -x "$cand/bin/java.exe" ]; then
                echo "$cand"; return 0
            fi
        done
    done
    return 1
}
