#update OMIM_PS_CUSTOM_DO table
. /etc/profile

APPNAME=OntologyLoad
APPDIR=/home/rgddata/pipelines/$APPNAME
cd $APPDIR
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

$APPDIR/_run.sh -omim_ps_custom_do_mapper "$@" 2>&1 > $APPDIR/logs/omim_ps_custom_do_mapper.log

mailx -s "[$SERVER] OMIM_PS_CUSTOM_DO mapper ok" mtutaj@mcw.edu < $APPDIR/logs/omim_ps_custom_do_mapper.log
