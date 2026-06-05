# Stage 1: Build the Java backend using Maven
FROM maven:3.8.5-openjdk-17-slim AS build
WORKDIR /build

# Copy the pom.xml and source code
COPY backend/pom.xml ./backend/
COPY backend/src ./backend/src/

# Build the fat jar
WORKDIR /build/backend
RUN mvn clean package -DskipTests

# Stage 2: Create the execution environment
FROM openjdk:17-jdk-slim
WORKDIR /app

# Copy the compiled fat jar from Stage 1
COPY --from=build /build/backend/target/ruteo-backend-1.0.0-jar-with-dependencies.jar ./app.jar

# Copy the static frontend files
COPY frontend ./frontend

# Copy the database SQL file for auto-loading if needed
COPY database/import.sql ./database/import.sql

# Set configuration environment variables
ENV PORT=8080
ENV FRONTEND_DIR=/app/frontend

# Expose the application port
EXPOSE 8080

# Command to run the application
CMD ["java", "-jar", "app.jar"]
