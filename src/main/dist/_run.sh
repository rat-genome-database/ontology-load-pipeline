# a wrapper script to run ontology-load-pipeline
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/ontology-load-pipeline

cd $APPDIR

java -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
     -Dspring.config=../properties/default_db2.xml \
     -jar lib/ontology-load-pipeline.jar "$@"
