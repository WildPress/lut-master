FROM gradle:8.11.1-jdk17
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
ENV PATH=$PATH:/opt/android/cmdline-tools/latest/bin:/opt/android/build-tools/35.0.0
USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/android/cmdline-tools \
 && curl -fsSL --retry 6 --retry-all-errors --retry-delay 5 -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/c.zip -d /opt/android/cmdline-tools && mv /opt/android/cmdline-tools/cmdline-tools /opt/android/cmdline-tools/latest && rm /tmp/c.zip
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager "platforms;android-35" "platforms;android-36" "build-tools;35.0.0" "platform-tools"
WORKDIR /work
