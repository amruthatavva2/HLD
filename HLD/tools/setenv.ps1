# Dot-source this script to put the portable JDK + Maven on PATH for the current shell:
#   . d:\HLD\tools\setenv.ps1
$env:JAVA_HOME = "d:\HLD\tools\jdk\jdk-17.0.19+10"
$env:MAVEN_HOME = "d:\HLD\tools\maven\apache-maven-3.9.9"
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
