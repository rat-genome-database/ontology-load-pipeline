# a wrapper script to run OntologyLoad pipeline
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad

cd $APPDIR

java -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
     -Dspring.config=../properties/default_db2.xml \
     -jar lib/OntologyLoad.jar "$@"
