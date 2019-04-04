# update stats for every ontology (affects table ONT_TERM_STATS)
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

$APPDIR/_run.sh -skip_downloads -qc_thread_count=8

$APPDIR/_run.sh -skip_downloads -gviewer_stats
