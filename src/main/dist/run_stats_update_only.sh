# update stats for every ontology
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

#update stats for all ontologies (affected table ONT_TERM_STATS2)
$APPDIR/_run.sh -skip_downloads

#update gviewer stats for all ontologies (affected table ONT_TERM_STATS)
$APPDIR/gviewer_stats.sh
cat $APPDIR/logs/gviewer_stats_summary.log
