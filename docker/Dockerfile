# Build environment for :core and :app. Versions are arguments so that step 1
# can pin them from what is current then (spec/04-design.md §1), not now.
ARG JDK_VERSION=17
FROM eclipse-temurin:${JDK_VERSION}-jdk

# Build number of the SDK command-line tools zip on developer.android.com.
ARG CMDLINE_TOOLS_BUILD=15859902
ARG ANDROID_PLATFORM=android-37.0
ARG BUILD_TOOLS=36.0.0

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl \
 && rm -rf /var/lib/apt/lists/*

# sdkmanager only finds itself when unpacked under cmdline-tools/latest.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip \
 && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
 && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm /tmp/cmdline-tools.zip

# sdkmanager now warns it is deprecated in favour of `android sdk`; it still
# works, and the switch belongs with the version pins at step 1.
RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
      "platform-tools" \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${BUILD_TOOLS}"

WORKDIR /work
