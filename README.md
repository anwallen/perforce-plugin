# perforce-plugin
**Note: This has been deprecated in favor of the pure-java p4-plugin**

Jenkins perforce plugin

Wiki / documentation: [Jenkins Perforce Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Perforce+Plugin)


Windows Building instructions (if the build.sh does not work for you):
Installers for Maven and Corretto are in P4: //depot/Development/Tools/Jenkins/installers

- Install Maven 3.8.6 
-- from https://maven.apache.org/download.cgi or P4
-- Unzip the folder to C:\Program Files\apache-maven-3.8.6
-- You can add Maven to your PATH Environment variable

- Install Amazon Corretto jdk 1.8.0_352 (x86 for now since we are running our server on Win2000)
-- from https://docs.aws.amazon.com/corretto/latest/corretto-8-ug/downloads-list.html or P4
-- If you are running a newer version of Java locally, do not update your JAVA_HOME or Path as part of the install


BUILDING

- Open a new command window (start->run> cmd or start it from P4)
- Navigate to your local copy of //depot/Development/Tools/Jenkins/plugins/perforce-plugin/

- Ensure Maven is in your PATH
	set PATH=C:\Program Files\apache-maven-3.8.6\bin;%PATH%
- Ensure the correct JDK 1.8 (x86) is in your path
	set PATH=C:\Program Files (x86)\Amazon Corretto\jdk1.8.0_352\bin;%PATH%
- Set your JAVA_HOME to point to the correct JDK
-- JAVA_HOME cannot contain any spaces, and escapes do not seem to work so the easiest way to deal with the path is to use the Windows short path
-- You can automatically set JAVA_HOME to the short path: 
	for %A in ("C:\Program Files (x86)\Amazon Corretto\jdk1.8.0_352") do set JAVA_HOME=%~sA

- Do the build and run the test server:
	mvn hpi:run
	
	If all goes well, Jenkins will start at http://localhost:8080/jenkins
	Test anything you need to test
	kill it with ctrl-c on the command line
- Deploy the HPI
