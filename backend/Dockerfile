FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /home/app
COPY pom.xml pom.xml
RUN mvn dependency:go-offline
COPY . .
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre AS deploy
WORKDIR /home/app

# Usamos el comodín *.jar para agarrar lo que sea que Maven haya compilado
# y lo renombramos simplemente a app.jar
COPY --from=build /home/app/target/*.jar /home/app/app.jar

EXPOSE 8080

# Ejecutamos el archivo con el nuevo nombre genérico
ENTRYPOINT ["java","-jar","/home/app/app.jar"]