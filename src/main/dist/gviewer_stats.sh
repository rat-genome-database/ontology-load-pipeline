#update gviewer_stats for all ontologies as specified in properties/AppConfigure.xml
. /etc/profile

APPNAME=OntologyLoad
APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
    -jar lib/${APPNAME}.jar -skip_downloads -gviewer_stats "$@" 2>&1

mailx -s "[$SERVER] GViewer Stats ok" mtutaj@mcw.edu < $APPDIR/logs/gviewer_stats_summary.log
