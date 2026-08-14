FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system feed && useradd --system --gid feed --home-dir /app --shell /usr/sbin/nologin feed \
    && mkdir -p /app/data/media && chown -R feed:feed /app
COPY --chown=feed:feed target/friend-feed-*.jar /app/app.jar
USER feed
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
