JDCloud CodeDeploy Plugin
========================= 


Introduction
------------

This plugin provides a "post-build" step for JDCloud CodeDeploy.

Development
-----------

Start the local Jenkins instance:

    mvn hpi:run

	
How to install
--------------

Run 

	mvn hpi:hpi
	
to create the plugin .hpi file.


To install:

1. copy the resulting ./target/jd-codedeploy.hpi file to the $JENKINS_HOME/plugins directory. Don't forget to restart Jenkins afterwards.
	
2. or use the plugin management console (http://example.com:8080/pluginManager/advanced) to upload the hpi file. You have to restart Jenkins in order to find the plugin in the installed plugins list.