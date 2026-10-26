# Gradle wrapper files + updated workflow
Create each file at the path after `=== FILE:`. The binary file gradle-wrapper.jar cannot be typed; the workflow generates it automatically.

=== FILE: gradlew ===
```
#!/bin/sh
# Minimal Gradle wrapper launcher (needs gradle/wrapper/gradle-wrapper.jar next to it).

app_path=$0
while [ -h "$app_path" ]; do
    ls=$(ls -ld "$app_path")
    link=${ls#*' -> '}
    case $link in
        /*) app_path=$link ;;
        *) app_path=$(dirname "$app_path")/$link ;;
    esac
done

APP_HOME=$(cd "$(dirname "$app_path")" > /dev/null && pwd -P) || exit

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew \
    -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
```

=== FILE: gradlew.bat ===
```
@rem Minimal Gradle wrapper launcher for Windows
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
    set JAVA_EXE=java.exe
)
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
```

=== FILE: gradle/wrapper/gradle-wrapper.properties ===
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

=== FILE: .github/workflows/build-apk.yml ===
```yaml
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Check out the code
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up the Android SDK
        uses: android-actions/setup-android@v3

      - name: Set up Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      # The wrapper jar is a binary file that cannot be typed by hand, so it is generated here.
      - name: Generate the Gradle wrapper
        run: |
          gradle wrapper --gradle-version 8.9 --distribution-type bin
          chmod +x gradlew

      - name: Build the APK
        run: ./gradlew :app:assembleRelease --no-daemon --stacktrace

      - name: Upload the APK
        uses: actions/upload-artifact@v4
        with:
          name: AiReply-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error

      # Download this once and add it to the repository if you ever open the project in Android Studio.
      - name: Upload the generated wrapper files
        uses: actions/upload-artifact@v4
        with:
          name: gradle-wrapper-files
          path: |
            gradlew
            gradlew.bat
            gradle/wrapper/gradle-wrapper.jar
            gradle/wrapper/gradle-wrapper.properties
          if-no-files-found: ignore

      - name: Upload build reports (only if something failed)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: build-reports
          path: app/build/reports
          if-no-files-found: ignore
```
