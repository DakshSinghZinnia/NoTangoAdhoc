# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre-jammy

# LibreOffice + tools to fetch fonts
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
      libreoffice-writer libreoffice-core libreoffice-common \
      fontconfig curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Poppins static TTFs in the correct system folder
RUN set -eux; \
    install -d -m 0755 /usr/share/fonts/truetype/poppins; \
    cd /usr/share/fonts/truetype/poppins; \
    base='https://raw.githubusercontent.com/google/fonts/main/ofl/poppins'; \
    for f in \
      Poppins-Black.ttf \
      Poppins-BlackItalic.ttf \
      Poppins-Bold.ttf \
      Poppins-BoldItalic.ttf \
      Poppins-ExtraBold.ttf \
      Poppins-ExtraBoldItalic.ttf \
      Poppins-ExtraLight.ttf \
      Poppins-ExtraLightItalic.ttf \
      Poppins-Italic.ttf \
      Poppins-Light.ttf \
      Poppins-LightItalic.ttf \
      Poppins-Medium.ttf \
      Poppins-MediumItalic.ttf \
      Poppins-Regular.ttf \
      Poppins-SemiBold.ttf \
      Poppins-SemiBoldItalic.ttf \
      Poppins-Thin.ttf \
      Poppins-ThinItalic.ttf; \
    do curl -fsSL -o "$f" "$base/$f"; done; \
    chmod 0644 *.ttf; \
    fc-cache -f -v; \
    fc-list | grep -i 'poppins' || true

# Optional: LO/JVM defaults that work well in containers
ENV OFFICE_HOME=/usr/lib/libreoffice
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

# --- your app bits below (optional) ---
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
COPY dd-java-agent.jar dd-java-agent.jar
COPY startup.sh /startup.sh
RUN chmod 644 /app/dd-java-agent.jar && chmod +x /startup.sh
ENTRYPOINT ["/startup.sh"]