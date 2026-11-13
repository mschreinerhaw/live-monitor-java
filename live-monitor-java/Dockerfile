FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/live-monitor-java-1.0.1.jar /app/live-monitor-java.jar
COPY templates/ /app/templates/
RUN mkdir -p /app/data /app/logs /app/config && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8000
VOLUME ["/app/data", "/app/logs", "/app/config"]
ENTRYPOINT ["java", "-jar", "/app/live-monitor-java.jar"]
