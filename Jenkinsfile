#!/usr/bin/env groovy

node{
    env.JAVA_HOME = ("${tool name: 'jdk-latest', type: 'jdk'}")
    env.PATH = ("${env.JAVA_HOME}/bin:${env.PATH}")

    stage('clean up'){
        cleanWs()
    }

    stage('scm'){
        checkout scm
    }

    stage('build and publish'){
        sh("./gradlew uploadArchives --stacktrace")
    }
}