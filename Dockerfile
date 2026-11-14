# Stage 1: Build frontend
FROM node:24-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
# The Supabase publishable key is not committed, so it arrives as a build argument. It ends up
# in the bundle either way — that is what a publishable key is for — but it stays out of the
# repository. The build fails without it rather than shipping a page that cannot sign anyone in.
ARG VITE_SUPABASE_URL
ARG VITE_SUPABASE_ANON_KEY
ENV VITE_SUPABASE_URL=$VITE_SUPABASE_URL
ENV VITE_SUPABASE_ANON_KEY=$VITE_SUPABASE_ANON_KEY
ARG VITE_PRIVACY_CONTROLLER_NAME
ARG VITE_PRIVACY_CONTACT_EMAIL
ENV VITE_PRIVACY_CONTROLLER_NAME=$VITE_PRIVACY_CONTROLLER_NAME
ENV VITE_PRIVACY_CONTACT_EMAIL=$VITE_PRIVACY_CONTACT_EMAIL
RUN npm run build

# Stage 2: Build Spring Boot (includes frontend static files)
FROM eclipse-temurin:25-jdk AS backend-build
WORKDIR /app
COPY backend/ ./
COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static/
RUN ./gradlew bootJar -x test --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
EXPOSE 8080
# Budget the whole process on the 1 GiB VM, not just its heap; leave native/OS headroom.
ENTRYPOINT ["java", "-Xmx320m", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=64m", "-XX:MaxDirectMemorySize=32m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
