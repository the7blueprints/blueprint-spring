FROM eclipse-temurin:21-jdk

WORKDIR /runner

COPY Runner.java .

RUN javac Runner.java

EXPOSE 8592

CMD ["java", "Runner"]
