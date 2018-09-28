# update stats for every ontology (affects table ONT_TERM_STATS)
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR
java -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
     -Dspring.config=../properties/default_db.xml \
     -jar OntologyLoad.jar \
     -skip_downloads \
     -qc_thread_count=8

java -Dlog4j.configuration=file://$APPDIR/properties/log4j.properties \
     -Dspring.config=../properties/default_db.xml \
     -jar OntologyLoad.jar \
     -skip_downloads \
     -gviewer_stats
