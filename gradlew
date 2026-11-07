#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit
if [ -n "$JAVA_HOME" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE=java
fi
exec "$JAVA_EXE" $JAVA_OPTS $GRADLE_OPTS "-Dorg.gradle.appname=gradlew" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
