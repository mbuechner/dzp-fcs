FROM maven:3-eclipse-temurin-25-noble AS mchain
WORKDIR /tmp/
COPY . .
RUN mvn package;

FROM tomcat:9-jre25-temurin-noble
LABEL maintainer="Michael Büchner <m.buechner@dnb.de>"

# Default-Webapps raus
RUN rm -rf "${CATALINA_HOME}/webapps/"*

# App kopieren
COPY --from=mchain /tmp/target/dzp-fcs.war ${CATALINA_HOME}/webapps/ROOT.war
# Enable GZIP compression
RUN sed -i 's#<Connector port="8080"#<Connector port="8080" compression="on"#' ${CATALINA_HOME}/conf/server.xml

# OpenShift-Compatibility
RUN chgrp -R 0 "${CATALINA_HOME}" \
 && chmod -R g=u "${CATALINA_HOME}"

EXPOSE 8080
