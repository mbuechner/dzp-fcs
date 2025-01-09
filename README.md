# SRU/CQL FCS 2.0 Endpoint for German Newspaper Portal

This application implements a [Federated Content Search (FCS)](https://www.clarin.eu/content/federated-content-search-clarin-fcs-technical-details) endpoint for the [German Newspaper Portal](https://www.deutsche-digitale-bibliothek.de/newspaper).

The aim of Federated Content Search is to enable a content search via distributed resources. The [German Digital Library](https://www.deutsche-digitale-bibliothek.de/) provides the German Newspaper Portal with a searchable corpus for this purpose.

The endpoint is published at the following URL: https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs

The following searches are given as examples.

* Search for ["Berlin"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin)
* Search for ["Berlin ist schön"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query="Berlin%20ist%20schön")
* Search for ["Berlin" and "Hamburg"](https://labs.deutsche-digitale-bibliothek.de/app/dzp-fcs?operation=searchRetrieve&query=Berlin%20AND%20Hamburg)

The endpoint is used for:

* Text+: [https://fcs.text-plus.org/?&query="Berlin ist schön"](https://fcs.text-plus.org/?&query="Berlin%20ist%20schön")
* Text+: [https://text-plus.org/en/](https://text-plus.org/en/) (Search → Content)
* SAW Leipzig: https://tppssi-demo.saw-leipzig.de/ (Search in content)
* etc.

## Implementation
This Java servlet was implemented using the [FCS Endpoint Archetype](https://github.com/clarin-eric/fcs-endpoint-archetype). Further information can be found there.

## Build
The build automation tool "Maven" can be used to create the Web Application Archive (WAR). The following command, executed in the folder containing the `pom.xml` file, creates a publishable WAR file.

```bash
mvn [clean] package
```

## Docker
Yes, there's a docker container for this application available at GitHub.

https://github.com/mbuechner/dzp-fcs/pkgs/container/dzp-fcs

### Container build

1.  Checkout GitHub repository:  
```bash
git clone https://github.com/mbuechner/dzp-fcs
```
3.  Go into folder:
```bash
cd dzp-fcs
```
4.  Run
```bash
docker build -t dzp-fcs .
```
5.  Start container with:
```bash
docker run -p 8080:8080 -P -e "TOMCAT_PASSWORD=verysecret" dzp-fcs
```
6.  Open browser:  [http://localhost:8080/](http://localhost:8080/)


### Environment variables

| Variable              | Description                                                                                                  | Default value                                                                    |
|-----------------------|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| TOMCAT_PASSWORD       | Apache Tomcat password.<br/>See [Apache Tomcat packaged by Bitnami](https://hub.docker.com/r/bitnami/tomcat) | No default                                                                       |
| DZP_FCS_SOLR_ENDPOINT | Endpoint url for the Solr search engine of German Newspaper Portal                                           | https://api.deutsche-digitale-bibliothek.de/search/index/newspaper-issues/select |
