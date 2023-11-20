FROM maven:3-openjdk-11-slim AS MAVEN_CHAIN
COPY pom.xml setenv.sh logging.properties /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN sed -i 's#<url-pattern>/\*</url-pattern>#<url-pattern>${URLPATTERN}</url-pattern>#' src/main/webapp/WEB-INF/web.xml && \
  mvn package

FROM jetty:10-jdk11
MAINTAINER Michael Büchner <m.buechner@dnb.de>
# RUN java -jar $JETTY_HOME/start.jar --add-modules=plus
COPY --from=MAVEN_CHAIN /tmp/target/dzp-fcs.war /var/lib/jetty/webapps/ROOT.war

EXPOSE 8080
