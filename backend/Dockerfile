FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /home/app

# Copiamos solo el pom.xml primero para cachear dependencias
# Si no cambia el pom.xml, Docker reutiliza esta capa en builds posteriores
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Ahora copiamos el código fuente
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS deploy
WORKDIR /home/app

# Usuario no-root por seguridad
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

COPY --from=build /home/app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]