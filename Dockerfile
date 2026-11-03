FROM maven:3-eclipse-temurin-25-noble AS mchain
WORKDIR /tmp/
COPY . .
RUN mvn -B -ntp package

FROM tomcat:9-jre25-temurin-noble
LABEL maintainer="Michael Büchner <m.buechner@dnb.de>"

# Remove the bundled demo applications.
RUN rm -rf "${CATALINA_HOME}/webapps/"*

# The entrypoint deploys this one WAR using the configured context path.
COPY --from=mchain /tmp/target/dzp-fcs.war ${CATALINA_HOME}/dzp-fcs/dzp-fcs.war
COPY config/docker-entrypoint.sh /usr/local/bin/dzp-fcs-entrypoint.sh
# Windows checkouts may use CRLF; sh requires Unix line endings.
RUN sed -i 's/\r$//' /usr/local/bin/dzp-fcs-entrypoint.sh
ENV DZP_FCS_CONTEXT_PATH=/

# Resolve connector and web.xml ${ENV_NAME:-default} values directly through Tomcat.
COPY config/tomcat/server.xml ${CATALINA_HOME}/conf/server.xml
RUN printf '\n%s\n' \
    'org.apache.tomcat.util.digester.PROPERTY_SOURCE=org.apache.tomcat.util.digester.EnvironmentPropertySource' \
    >> "${CATALINA_HOME}/conf/catalina.properties"

# OpenShift-Compatibility
RUN chgrp -R 0 "${CATALINA_HOME}" \
 && chmod -R g=u "${CATALINA_HOME}"

EXPOSE 8080
ENTRYPOINT ["sh", "/usr/local/bin/dzp-fcs-entrypoint.sh"]
CMD ["catalina.sh", "run"]
