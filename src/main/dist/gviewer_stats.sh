#update gviewer_stats for all ontologies as specified in properties/AppConfigure.xml
. /etc/profile

APPNAME=OntologyLoad
APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

$APPDIR/_run.sh -skip_downloads -gviewer_stats "$@" 2>&1

mailx -s "[$SERVER] GViewer Stats ok" mtutaj@mcw.edu < $APPDIR/logs/gviewer_stats_summary.log
