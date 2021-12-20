# a wrapper script to run OntologyLoad pipeline
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad

cd $APPDIR

java -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
     -Dspring.config=../properties/default_db2.xml \
     -jar lib/OntologyLoad.jar "$@"
