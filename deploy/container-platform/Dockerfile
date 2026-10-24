ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${JAVA_RUNTIME_IMAGE}
ARG SOURCE_REVISION=uncommitted
LABEL org.opencontainers.image.title="Live Monitor Container Operations Platform" \
      org.opencontainers.image.revision=${SOURCE_REVISION}
WORKDIR /app
RUN mkdir -p /app/data /app/logs /app/templates && chown -R 10001:10001 /app
COPY --chown=10001:10001 app.jar /app/app.jar
USER 10001:10001
ENV LANG=C.UTF-8 TZ=Asia/Shanghai
EXPOSE 8000
HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=3 CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8000; printf 'GET /api/health HTTP/1.0\r\n\r\n' >&3; IFS= read -r line <&3; [[ $line == *' 200 '* ]]"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
