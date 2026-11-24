#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "Official Gradle wrapper JAR is missing. On Windows run ПОДГОТОВИТЬ_GRADLE.cmd first." >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ]; then JAVA_EXE="$JAVA_HOME/bin/java"; else JAVA_EXE=java; fi
exec "$JAVA_EXE" -Xmx64m -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
