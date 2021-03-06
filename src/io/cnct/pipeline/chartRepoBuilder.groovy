// /src/io/cnct/pipeline/chartRepoBuilder.groovy
package io.cnct.pipeline;

def executePipeline(pipelineDef) {
  // script globals initialized in initializeHandler
  isChartChange = false
  isMasterBuild = false
  isPRBuild = false
  isSelfTest = false
  pipeline = pipelineDef
  pipelineEnvVariables = []
  pullSecrets = []
  defaults = parseYaml(libraryResource("io/cnct/pipeline/defaults.yaml"))
  slackError = ""

  properties(
    [
      disableConcurrentBuilds()
    ]
  )

  def err = null
  def notifyMessage = ""

  try {
    initializeHandler();

    if (isPRBuild || isSelfTest) {
      runPR()
    }

    if (isSelfTest) {
      initializeHandler();
    }

    if (isMasterBuild || isSelfTest) {
      runMerge()
    }

    notifyMessage = 'Build succeeded for ' + "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL})"
  } catch (e) {
    currentBuild.result = 'FAILURE'
    notifyMessage = 'Build failed for ' + 
      "${env.JOB_NAME} number ${env.BUILD_NUMBER} (${env.RUN_DISPLAY_URL}) : ${e.getMessage()}"
    err = e
  } finally {

    postCleanup(err)
    
    if (err) {
      slackFail(pipeline, notifyMessage)

      def sw = new StringWriter()
      def pw = new PrintWriter(sw)
      err.printStackTrace(pw)
      echo sw.toString()
      
      throw err
    } else {
      slackOk(pipeline, notifyMessage)
    } 
  }
}

// try to cleanup any hanging helm release or namespaces resulting from premature termination
// through either job ABORT or error
def postCleanup(err) {
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
    inside(label: buildId('tools')) {
      container('helm') {
        stage('Cleaning up') {

          // always cleanup workspace pvc
          sh("kubectl delete pvc jenkins-workspace-${kubeName(env.JOB_NAME)} --namespace ${defaults.jenkinsNamespace} || true")
          // always cleanup var lib docker pvc
          sh("kubectl delete pvc jenkins-varlibdocker-${kubeName(env.JOB_NAME)} --namespace ${defaults.jenkinsNamespace} || true")
          // always cleanup storageclass
          sh("kubectl delete storageclass jenkins-storageclass-${kubeName(env.JOB_NAME)} || true")
          // always clean up pull secrets
          def deletePullSteps = [:]
          for (pull in pipeline.pullSecrets ) {
            def deleteSecrets = "kubectl delete secret ${pull.name}-${kubeName(env.JOB_NAME)} --namespace=${defaults.jenkinsNamespace} || true"
            deletePullSteps["${pull.name}-${kubeName(env.JOB_NAME)}"] = { sh(deleteSecrets) }
          }
          parallel deletePullSteps

          if (isPRBuild || isSelfTest) {

            // unstash kubeconfig files
            unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

            // clean up failed releases if present
            withEnv(
            [
              "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
            ]) {
              sh("helm list --namespace ${kubeName(env.JOB_NAME)} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
              sh("helm list --namespace ${pipeline.stage.namespace} --short --failed --tiller-namespace ${pipeline.helm.namespace} | while read line; do helm delete \$line --purge --tiller-namespace ${pipeline.helm.namespace}; done")
            }

            // additional cleanup on error that destroy handler might have missed
            if (err) {
              def helmCleanSteps = [:] 

              for (chart in pipeline.deployments) {
                if (chart.chart) {
                  def commandString = "helm delete ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --purge --tiller-namespace ${pipeline.helm.namespace} || true"
                  helmCleanSteps["${chart.chart}-deploy-test"] = { 
                    withEnv(
                      [
                        "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
                      ]) {
                      sh(commandString) 
                    }
                  }
                }
              }

              echo("Contents of ${kubeName(env.JOB_NAME)} namespace:")
              sh("kubectl describe all --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")

              parallel helmCleanSteps

              sh("kubectl delete namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")
            }
          }
        }
      }
    }
  }
}

def initializeHandler() {
  def scmVars
  
  // init all the conditionals we care about
  // This is a PR build if CHANGE_ID is set by git SCM
  isPRBuild = (env.CHANGE_ID) ? true : false
  // This is a master build if this is not a PR build
  isMasterBuild = !isPRBuild
  // TODO: this would be initialized from a job parameter.
  isSelfTest = false


  // collect the env values to be injected
  pipelineEnvVariables += containerEnvVar(key: 'DOCKER_HOST', value: 'localhost:2375')
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_ADDR', value: pipeline.vault.server)
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CACERT', value: "/etc/vault/tls/${pipeline.vault.tls.ca}")
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CLIENT_CERT', value: "/etc/vault/tls/${pipeline.vault.tls.cert}")
  pipelineEnvVariables += containerEnvVar(key: 'VAULT_CLIENT_KEY', value: "/etc/vault/tls/${pipeline.vault.tls.key}")

  // create pull secrets 
  withTools(
    envVars: pipelineEnvVariables, 
    defaults: defaults,
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
    inside(label: buildId('tools')) {
      
      // cleanup workspace
      deleteDir()
      
      scmVars = checkout scm
      container('helm') {
        stage('Create jenkins storage class') {
          echo('Loading jenkins storage class template')
          def storageClass = parseYaml(libraryResource("io/cnct/pipeline/jenkins-storage-class.yaml"))
          storageClass.metadata.name = "jenkins-storageclass-${kubeName(env.JOB_NAME)}".toString()
          storageClass.parameters.zones = defaults.pvcZone
          toYamlFile(storageClass, "${pwd()}/jenkins-storageclass-${kubeName(env.JOB_NAME)}.yaml")

          echo('creating jenkins storage class')
          sh("cat ${pwd()}/jenkins-storageclass-${kubeName(env.JOB_NAME)}.yaml")
          sh("kubectl create -f ${pwd()}/jenkins-storageclass-${kubeName(env.JOB_NAME)}.yaml")
        }

        stage('Create workspace pvc') {
          echo('Loading pipeline worskspace pvc template')
          def workspaceInfo = parseYaml(libraryResource("io/cnct/pipeline/utility-pvc.yaml"))
          workspaceInfo.metadata.name = "jenkins-workspace-${kubeName(env.JOB_NAME)}".toString()
          workspaceInfo.spec.resources.requests.storage = defaults.workspaceSize
          workspaceInfo.spec.storageClassName = "jenkins-storageclass-${kubeName(env.JOB_NAME)}".toString()
          toYamlFile(workspaceInfo, "${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml")

          echo('creating workspace pvc')
          sh("cat ${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml")
          sh("kubectl create -f ${pwd()}/jenkins-workspace-${kubeName(env.JOB_NAME)}.yaml --namespace ${defaults.jenkinsNamespace}")
        }

        stage('Create var/lib/docker pvc') {
          echo('Loading var/lib/docker pvc template')
          def dockerInfo = parseYaml(libraryResource("io/cnct/pipeline/utility-pvc.yaml"))
          dockerInfo.metadata.name = "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}".toString()
          dockerInfo.spec.resources.requests.storage = defaults.dockerBuilderSize
          dockerInfo.spec.storageClassName = "jenkins-storageclass-${kubeName(env.JOB_NAME)}".toString()
          toYamlFile(dockerInfo, "${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml")

          echo('creating var/lib/docker pvc')
          sh("cat ${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml")
          sh("kubectl create -f ${pwd()}/jenkins-varlibdocker-${kubeName(env.JOB_NAME)}.yaml --namespace ${defaults.jenkinsNamespace}")
        }

        stage('Create image pull secrets') {
          def createPullSteps = [:]
          for (pull in pipeline.pullSecrets ) {
            withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
              def vaultToken = env.VAULT_TOKEN
              def secretVal = getVaultKV(
                defaults,
                vaultToken,
                pull.password)

              def createSecrets = """
                set +x
                kubectl create secret docker-registry ${pull.name}-${kubeName(env.JOB_NAME)} \
                  --docker-server=${pull.server} \
                  --docker-username=${pull.username} \
                  --docker-password='${secretVal}' \
                  --docker-email='${pull.email}' --namespace=${defaults.jenkinsNamespace}
                set -x"""

              pullSecrets += "${pull.name}-${kubeName(env.JOB_NAME)}"
              createPullSteps["${pull.name}-${kubeName(env.JOB_NAME)}"] = { sh(createSecrets) }
            }
          }

          parallel createPullSteps
        }

        stage('Get target cluster configuration') {
          withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
            def vaultToken = env.VAULT_TOKEN

            if (isPRBuild || isSelfTest) {
              def testKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.testCluster)
              writeFile(file: "${env.BUILD_ID}-test.kubeconfig", text: testKubeconfig)
              def stagingKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.stagingCluster)
              writeFile(file: "${env.BUILD_ID}-staging.kubeconfig", text: stagingKubeconfig)
            }

            if (isMasterBuild || isSelfTest) {
              def prodKubeconfig = getVaultKV(
                defaults,
                vaultToken,
                defaults.targets.prodCluster)
              writeFile(file: "${env.BUILD_ID}-prod.kubeconfig", text: prodKubeconfig)
            }
          }

          stash(
            name: "${env.BUILD_ID}-kube-configs".replaceAll('-','_'),
            includes: "**/*.kubeconfig"
          )
        }
      }

      container('vault') {
        stage('Set global environment variables') {          
          for (envValue in pipeline.envValues ) {
            if (envValue.secret) {
              withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                def vaultToken = env.VAULT_TOKEN
                def secretVal = getVaultKV(
                  defaults,
                  vaultToken,
                  envValue.secret)
                pipelineEnvVariables += envVar(
                  key: envValue.envVar, 
                  value: secretVal)
              }
            } else if (envValue.env) {
              
              def valueFromEnv = ""
              if (env[envValue.env]) {
                valueFromEnv = env[envValue.env]
              } else {
                valueFromEnv = scmVars[envValue.env]
              }

              pipelineEnvVariables += envVar(
                  key: envValue.envVar, 
                  value: valueFromEnv)  
            } else {
              pipelineEnvVariables += envVar(
                key: envValue.envVar, 
                value: envValue.value)
            }
          }
        }
      }
    }
  } 
}

def runPR() {
  def scmVars
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    workspaceClaimName: "jenkins-workspace-${kubeName(env.JOB_NAME)}",
    dockerClaimName: "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}",
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {       
    
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }
    

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      buildsTestHandler(scmVars)
      chartLintHandler(scmVars)

      deployToTestHandler(scmVars)
      helmTestHandler(scmVars)
      testTestHandler(scmVars)
      destroyHandler(scmVars)
      
      buildsStageHandler(scmVars)
      deployToStageHandler(scmVars)
      stageTestHandler(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

def runMerge() {
  def scmVars
  withTools(
    defaults: defaults,
    envVars: pipelineEnvVariables,
    containers: getScriptImages(),
    imagePullSecrets: pullSecrets,
    workspaceClaimName: "jenkins-workspace-${kubeName(env.JOB_NAME)}",
    dockerClaimName: "jenkins-varlibdocker-${kubeName(env.JOB_NAME)}",
    volumes: [secretVolume(secretName: pipeline.vault.tls.secret, mountPath: '/etc/vault/tls')]) {
    inside(label: buildId('tools')) {
      stage('Checkout') {
        scmVars = checkout scm
      }

      // run before scripts
      executeUserScript('Executing global \'before\' scripts', pipeline.beforeScript)

      buildsProdHandler(scmVars)
      chartProdHandler(scmVars)
      deployToProdHandler(scmVars)
      chartProdVersion(scmVars)
      startTriggers(scmVars)

      // run after scripts
      executeUserScript('Executing global \'after\' scripts', pipeline.afterScript)
    }
  }
}

// Build changed builds folders 
// Tag with commit sha
// Tag with a test tag
// then push to repo
def buildsTestHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBinaryBuildSteps = [:]
  def binaryBuildCounter = 0
  def parallelContainerBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  executeUserScript('Executing test \'before\' script', pipeline.test.beforeScript)

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  container('vault') {
    stage('Collect build targets') {
      for (container in pipeline.builds) {
        if (container.script || container.commands) {
          def description = "Executing binary build ${binaryBuildCounter} using ${container.image}"
          def _container = container
          parallelBinaryBuildSteps["binary-build-${binaryBuildCounter}"] = { 
            executeUserScript(description, _container) 
          }
          binaryBuildCounter += 1 
        } else {
          // build steps
          def buildCommandString = "docker build -t\
            ${defaults.docker.registry}/${container.image}:${useTag} --pull " 
          if (container.buildArgs) {
            def argMap = [:]

            for (buildArg in container.buildArgs) {
              if (buildArg.secret) {
                withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                  def vaultToken = env.VAULT_TOKEN
                  def secretVal = getVaultKV(
                    defaults,
                    vaultToken,
                    buildArg.secret)
                  argMap += ["${buildArg.arg}" : "${secretVal.trim()}"]
                }
              } else if (buildArg.env) {
                
                def valueFromEnv = ""
                if (env[buildArg.env]) {
                  valueFromEnv = env[buildArg.env]
                } else {
                  valueFromEnv = scmVars[buildArg.env]
                }

                argMap += ["${buildArg.arg}" : "${valueFromEnv.trim()}"]  
              } else {
                argMap += ["${buildArg.arg}" : "${buildArg.value.trim()}"]
              }
            }

            buildCommandString += mapToParams('--build-arg', argMap)
          }
          buildCommandString += " ${container.dockerContext} --file ${dockerfileLocation(defaults, container.context)}"

          parallelContainerBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }


          // tag steps
          def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag}\
           ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
          parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

          // push steps
          def pushShaCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
          def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.testTag}"
          
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha"] = { sh(pushShaCommandString) }
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

          
          if (container.chart) {
            chartsWithContainers += container
          }
        }
      }
    }
  }

  // build binaries
  stage('Build binaries') {
    parallel parallelBinaryBuildSteps
  }

  // build containers
  container('docker') {
    stage("Building docker files, tagging with ${gitCommit} and ${defaults.docker.testTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelContainerBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash 
      for (chart in chartsWithContainers) { 
        def valuesYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }
}

// Tag changed repos with a stage tag
// then push to repo
def buildsStageHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  executeUserScript('Executing stage \'before\' script', pipeline.stage.beforeScript)

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  for (container in pipeline.builds) {
    if (!container.script && !container.commands) {
      // tag steps
      def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
       ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
      parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }

      // push steps
      def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.stageTag}"
      parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }

      if (container.chart) {
        chartsWithContainers += container
      }
    }
  }

  container('docker') {
    stage("Tagging with ${defaults.docker.stageTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash
      for (chart in chartsWithContainers) {
        def valuesYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }
}

// tag changed builds folders with prod tag
// then push to repo
def buildsProdHandler(scmVars) {
  def gitCommit = scmVars.GIT_COMMIT
  def chartsWithContainers = []

  def parallelBinaryBuildSteps = [:]
  def binaryBuildCounter = 0
  def parallelBuildSteps = [:]
  def parallelTagSteps = [:]
  def parallelPushSteps = [:]

  executeUserScript('Executing prod \'before\' script', pipeline.prod.beforeScript)

  // get tag text
  def useTag = makeDockerTag(defaults, gitCommit)

  // Collect all the docker build steps as 'docker build' command string
  // for later execution in parallel
  // Also memoize the builds objects, if they are connected to in-repo charts
  container('vault') {
    stage('Collect build targets') {
      for (container in pipeline.builds) {
        if (container.script || container.commands) {
          def description = "Executing binary build ${binaryBuildCounter} using ${container.image}"
          def _container = container
          parallelBinaryBuildSteps["binary-build-${binaryBuildCounter}"] = { 
            executeUserScript(description, _container) 
          }
          binaryBuildCounter += 1 
        } else {
          // build steps
          def buildCommandString = "docker build -t \
            ${defaults.docker.registry}/${container.image}:${useTag} --pull " 
          if (container.buildArgs) {
            def argMap = [:]

            for (buildArg in container.buildArgs) {
              if (buildArg.secret) {
                withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
                  def vaultToken = env.VAULT_TOKEN
                  def secretVal = getVaultKV(
                    defaults,
                    vaultToken,
                    buildArg.secret)
                  argMap += ["${buildArg.arg}" : "${secretVal.trim()}"]
                }
              } else if (buildArg.env) {
                
                def valueFromEnv = ""
                if (env[buildArg.env]) {
                  valueFromEnv = env[buildArg.env]
                } else {
                  valueFromEnv = scmVars[buildArg.env]
                }

                argMap += ["${buildArg.arg}" : "${valueFromEnv.trim()}"]  
              } else {
                argMap += ["${buildArg.arg}" : "${buildArg.value.trim()}"]
              }
            }
            
            buildCommandString += mapToParams('--build-arg', argMap)
          }
          buildCommandString += " ${container.dockerContext} --file ${dockerfileLocation(defaults, container.context)}"
          parallelBuildSteps["${container.image.replaceAll('/','_')}-build"] = { sh(buildCommandString) }

          def tagCommandString = "docker tag ${defaults.docker.registry}/${container.image}:${useTag} \
           ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
          parallelTagSteps["${container.image.replaceAll('/','_')}-tag"] = { sh(tagCommandString) }


          def pushShaCommandString = "docker push ${defaults.docker.registry}/${container.image}:${useTag}"
          def pushTagCommandString = "docker push ${defaults.docker.registry}/${container.image}:${defaults.docker.prodTag}"
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-tag"] = { sh(pushTagCommandString) }
          parallelPushSteps["${container.image.replaceAll('/','_')}-push-sha"] = { sh(pushShaCommandString) }

          if (container.chart) {
            chartsWithContainers += container
          }
        }
      }
    }
  }

  // build binaries
  stage('Build binaries') {
    parallel parallelBinaryBuildSteps
  }

  container('docker') {
    stage("Building docker files, tagging with ${defaults.docker.prodTag} and pushing.") {
      withCredentials(
        [usernamePassword(
          credentialsId: defaults.docker.credentials, 
          usernameVariable: 'DOCKER_USER',
          passwordVariable: 'DOCKER_PASSWORD')]) {
        sh('docker login --username $DOCKER_USER --password $DOCKER_PASSWORD ' + defaults.docker.registry)
      }

      parallel parallelBuildSteps
      parallel parallelTagSteps
      parallel parallelPushSteps

      // process values yamls for modified charts
      // modify the appropriate image objects under values yaml to point to the newly tagged image
      // write back to values.yaml and stash
      for (chart in chartsWithContainers) {
        def valuesYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml"))

        mapValueByPath(chart.value, valuesYaml, "${defaults.docker.registry}/${chart.image}:${useTag}")
        toYamlFile(valuesYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/values.yaml")

        stash(
          name: "${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'),
          includes: "${chartLocation(defaults, chart.chart)}/values.yaml"
        )
      }
    }
  }
}

// Use helm lint to go over everything that changed 
// under /charts folder
def chartLintHandler(scmVars) { 
  def parallelLintSteps = [:]   
  def versionFileContents = readFile(defaults.versionfile).trim() 

  // read in all appropriate versionfiles and replace Chart.yaml versions 
  // this will verify that version files had helm-valid version numbers during linting step
  for (chart in pipeline.deployments) { 
    if (chart.chart) {
      // load chart yaml
      def chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml"))

      // build new chart version
      def verComponents = []
      verComponents.addAll(chartYaml.version.toString().split('\\+'))

      if (verComponents.size() > 1) {
        verComponents[1] = scmVars.GIT_COMMIT
      } else {
        verComponents << scmVars.GIT_COMMIT
      }

      verComponents[0] = versionFileContents + "-test.${env.BUILD_NUMBER}"
      
      chartYaml.version = verComponents.join('+')

      toYamlFile(chartYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml")

      // stash the Chart.yaml
      stash(
        name: "${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
        includes: "${chartLocation(defaults, chart.chart)}/Chart.yaml"
      )

      // grab current config object that is applicable to test section from all deployments
      def commandString = "helm lint ${chartLocation(defaults, chart.chart)}"
      parallelLintSteps["${chart.chart}-lint"] = { sh(commandString) }
    } 
  }

  container('helm') {
    stage('Linting charts') {
      for (chart in pipeline.deployments) {
        // unstash chart yaml changes
        unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

        // unstash values changes if applicable
        unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))
      }

      parallel parallelLintSteps
    }
  }
}

// upload charts to helm registry
def chartProdHandler(scmVars) {
  def versionFileContents = readFile(defaults.versionfile).trim()
  def parallelChartSteps = [:] 
  
  container('helm') {
    stage('Preparing chart for prod') {
      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // load chart yaml
          def chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml"))

          // build new chart version
          def verComponents = []
          verComponents.addAll(chartYaml.version.toString().split('\\+'))

          if (verComponents.size() > 1) {
            verComponents[1] = scmVars.GIT_COMMIT
          } else {
            verComponents << scmVars.GIT_COMMIT
          }

          verComponents[0] = versionFileContents + "-prod.${env.BUILD_NUMBER}"

          chartYaml.version = verComponents.join('+')
          toYamlFile(chartYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml")

          // stash the Chart.yaml
          stash(
            name: "${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
            includes: "${chartLocation(defaults, chart.chart)}/Chart.yaml"
          )

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // package chart, send it to registry
          parallelChartSteps["${chart.chart}-upload"] = {
            withCredentials(
              [usernamePassword(
                credentialsId: defaults.helm.credentials, 
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASSWORD')]) {
                def registryUser = env.REGISTRY_USER
                def registryPass = env.REGISTRY_PASSWORD
                def helmCommand = """helm init --client-only
                  helm repo add pipeline https://${defaults.helm.registry}"""

                for (repo in pipeline.helmRepos) {
                  helmCommand = "${helmCommand}\nhelm repo add ${repo.name} ${repo.url}"
                }

                helmCommand = """${helmCommand}
                  helm dependency update --debug ${chartLocation(defaults, chart.chart)}
                  helm package --debug ${chartLocation(defaults, chart.chart)}
                  curl -u ${registryUser}:${registryPass} --data-binary @${chart.chart}-${chartYaml.version}.tgz https://${defaults.helm.registry}/api/charts"""

                sh(helmCommand)
            }
          }
        }
      }

      parallel parallelChartSteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into testing namespace
def deployToTestHandler(scmVars) {
    
  container('helm') {
    stage('Deploying to test namespace') {
      def deploySteps = [:]

      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {
          // unstash chart yaml if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // deploy chart to the correct namespace
          def commandString = """
            set +x
            helm init --client-only
            helm repo add pipeline https://${defaults.helm.registry}"""

          for (repo in pipeline.helmRepos) {
            commandString = "${commandString}\nhelm repo add ${repo.name} ${repo.url}"
          }

          commandString = """${commandString}
          helm dependency update --debug ${chartLocation(defaults, chart.chart)}
          helm package --debug ${chartLocation(defaults, chart.chart)}
          helm install ${chartLocation(defaults, chart.chart)} --wait --timeout ${chart.timeout} --tiller-namespace ${pipeline.helm.namespace} --namespace ${kubeName(env.JOB_NAME)} --name ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))}"""

          
          def setParams = envMapToSetParams(chart.test.values)
          commandString += setParams

          deploySteps["${chart.chart}-deploy-test"] = { 
            withEnv(
            [
              "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
            ]) {
              sh(commandString)
            }  
          }
        }
      }

      parallel deploySteps
    }

    stage('Archive artifacts') {
      archiveArtifacts(artifacts: '*.tgz', allowEmptyArchive: true)
    }
  }
}

// deploy chart from source into staging namespace
def deployToStageHandler(scmVars) { 
  
  if (pipeline.stage.deploy) {
    container('helm') {
      stage('Deploying to stage namespace') {
        def deploySteps = [:]
        
        // unstash kubeconfig files
        unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

        for (chart in pipeline.deployments) {
          if (chart.chart) {
            // unstash chart yaml if applicable
            unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

            // unstash values changes if applicable
            unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

            // deploy chart to the correct namespace
            def commandString = """
            set +x
            helm init --client-only
            helm dependency update --debug ${chartLocation(defaults, chart.chart)}
            helm upgrade --install --tiller-namespace ${pipeline.helm.namespace} --wait --timeout ${chart.timeout} --namespace ${pipeline.stage.namespace} ${helmReleaseName(chart.release + "-" + pipeline.stage.namespace)} ${chartLocation(defaults, chart.chart)}""" 
            
            def setParams = envMapToSetParams(chart.stage.values)
            commandString += setParams

            deploySteps["${chart.chart}-deploy-stage"] = { 
              withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-staging.kubeconfig"
              ]) {
                sh(commandString)
              }
            }
          }
        }

        parallel deploySteps 
        createCert(pipeline.stage.namespace)            
      }
    }
  }
}

// deploy chart from repository into prod namespace, 
// conditional on doDeploy
def deployToProdHandler(scmVars) { 
  def versionfileChanged = isPathChange(defaults.versionfile, "${env.CHANGE_ID}")

  container('helm') {
    def deploySteps = [:]
    stage('Deploying to prod namespace') {

      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // unstash chart yaml changes if applicable
          unstashCheck("${chart.chart}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'))

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml"))

          // determine if we need to deploy
          def doDeploy = false
          if (pipeline.prod.doDeploy == 'auto') {
            doDeploy = true
          } else if (pipeline.prod.doDeploy == 'versionfile') {
            if (versionfileChanged == 0) {
              doDeploy = true
            }
          }

          // deploy chart to the correct namespace
          if (doDeploy) {
            def commandString = """set +x
              helm init --client-only
              helm repo add pipeline https://${defaults.helm.registry}"""

            for (repo in pipeline.helmRepos) {
              commandString = "${commandString}\nhelm repo add ${repo.name} ${repo.url}"
            }

            commandString = """${commandString}
            helm dependency update --debug ${chartLocation(defaults, chart.chart)}
            helm upgrade --install --wait --timeout ${chart.timeout} --tiller-namespace ${pipeline.helm.namespace} --repo https://${defaults.helm.registry} --version ${chartYaml.version} --namespace ${pipeline.prod.namespace} ${helmReleaseName(chart.release)} ${chart.chart} """
            
            def setParams = envMapToSetParams(chart.prod.values)
            commandString += setParams

            deploySteps["${chart.chart}-deploy-prod"] = { 
              withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-prod.kubeconfig"
              ]) {
                sh(commandString)
              }
            }
          }
        }
      }

      parallel deploySteps
      createCert(pipeline.prod.namespace)                    
    }
  }

  executeUserScript('Executing prod \'after\' script', pipeline.prod.afterScript)
}

def chartProdVersion(scmVars) {
  def versionFileContents = readFile(defaults.versionfile).trim()
  def parallelChartSteps = [:] 
  
  container('helm') {
    stage('Preparing chart for production version') {
      for (chart in pipeline.deployments) {
        if (chart.chart) {

          // load chart yaml
          def chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml"))
          chartYaml.version = versionFileContents
          toYamlFile(chartYaml, "${pwd()}/${chartLocation(defaults, chart.chart)}/Chart.yaml")

          // unstash values changes if applicable
          unstashCheck("${chart.chart}-values-${env.BUILD_ID}".replaceAll('-','_'))

          // package chart, send it to registry
          parallelChartSteps["${chart.chart}-upload"] = {
            withCredentials(
              [usernamePassword(
                credentialsId: defaults.helm.credentials, 
                usernameVariable: 'REGISTRY_USER',
                passwordVariable: 'REGISTRY_PASSWORD')]) {
                def registryUser = env.REGISTRY_USER
                def registryPass = env.REGISTRY_PASSWORD
                def helmCommand = """helm init --client-only
                  helm repo add pipeline https://${defaults.helm.registry}"""

                for (repo in pipeline.helmRepos) {
                  helmCommand = "${helmCommand}\nhelm repo add ${repo.name} ${repo.url}"
                }

                helmCommand = """${helmCommand}
                  helm dependency update --debug ${chartLocation(defaults, chart.chart)}
                  helm package --debug ${chartLocation(defaults, chart.chart)}
                  curl -u ${registryUser}:${registryPass} --data-binary @${chart.chart}-${chartYaml.version}.tgz https://${defaults.helm.registry}/api/charts"""

                sh(helmCommand)
            }
          }
        }
      }

      parallel parallelChartSteps
    }
  }
}

// start any of the defined triggers, if present
def startTriggers(scmVars) {
  def triggerSteps = [:]
  try {
    configFileProvider([configFile(fileId: "${env.JOB_NAME.split('/')[0]}-dependencies", variable: 'TRIGGER_PIPELINES')]) {
      def triggerPipelines = readFile(env.TRIGGER_PIPELINES).tokenize(',').unique()
      for (trigger in triggerPipelines) {
        triggerSteps[trigger.toString()] = { build(job: "${trigger}/master", propagate: true, wait: true) }
      }
    }
  } catch (e) {
    echo("No triggers defined.")
  }

  if (triggerSteps.size() > 0) {
    parallel triggerSteps
  }
}

// run helm tests
def helmTestHandler(scmVars) {
  container('helm') {
    stage('Running helm tests') {
      
      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      for (chart in pipeline.deployments) {
        if (chart.chart) {
          def commandString = """
          helm test --tiller-namespace ${pipeline.helm.namespace} --timeout ${chart.timeout} ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --debug
          """ 

          retry(chart.retries) {
            try {
              withEnv(
                [
                  "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
                ]) {
                sh(commandString)
              }
            } finally {
              def logString = "kubectl get pods --kubeconfig=${env.BUILD_ID}-test.kubeconfig --namespace ${kubeName(env.JOB_NAME)} -o go-template\
                  --template='{{range .items}}{{\$name := .metadata.name}}{{range \$key,\
                  \$value := .metadata.annotations}}{{\$name}} {{\$key}}:{{\$value}}+{{end}}{{end}}'\
                  | tr '+' '\\n' | grep -e helm.sh/hook:.*test-success -e helm.sh/hook:.*test-failure |\
                  cut -d' ' -f1 | while read line;\
                  do kubectl logs \$line --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig;\
                  kubectl delete pod \$line --namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig; done"
              sh(logString)
            }
          }
        }
      }
    }
  }
}

// run test tests
def testTestHandler(scmVars) {

  for (config in pipeline.deployments) {
    if (config.test.tests) {
      for (test in config.test.tests) {
        executeUserScript('Executing test test scripts', test, ["KUBECONFIG=${pwd()}/${env.BUILD_ID}-test.kubeconfig"])
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.test.afterScript) 
}

// run staging tests
def stageTestHandler(scmVars) {

  for (config in pipeline.deployments) {
    if (config.stage.tests) {
      for (test in config.stage.tests) {
        executeUserScript('Executing staging test scripts', test, ["KUBECONFIG=${pwd()}/${env.BUILD_ID}-staging.kubeconfig"])
      }
    }
  }

  executeUserScript('Executing stage \'after\' script', pipeline.stage.afterScript) 
}

// destroy the test namespace
def destroyHandler(scmVars) {
  def destroySteps = [:]

  container('helm') {
    stage('Cleaning up test') {
      
      // unstash kubeconfig files
      unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

      echo("Contents of ${kubeName(env.JOB_NAME)} namespace:")
      sh("kubectl describe all --kubeconfig=${env.BUILD_ID}-test.kubeconfig --namespace ${kubeName(env.JOB_NAME)} || true")
      
      for (chart in pipeline.deployments) {
        if (chart.chart) {
          def commandString = "helm delete ${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))} --purge --tiller-namespace ${pipeline.helm.namespace}"
          destroySteps["${helmReleaseName(chart.release + "-" + kubeName(env.JOB_NAME))}"] = { 
            withEnv(
              [
                "KUBECONFIG=${env.BUILD_ID}-test.kubeconfig"
              ]) {
              sh(commandString)
            } 
          }
        }
      }

      parallel destroySteps

      sh("kubectl delete namespace ${kubeName(env.JOB_NAME)} --kubeconfig=${env.BUILD_ID}-test.kubeconfig || true")
    }
  }

  executeUserScript('Executing test \'after\' script', pipeline.test.afterScript)
}

def envMapToSetParams(envMap) {
  def setParamString = ""
  for (obj in envMap) {
    if (obj.key) {
      if (obj.secret) {
        setParamString += " --set ${obj.key}="
        withCredentials([string(credentialsId: defaults.vault.credentials, variable: 'VAULT_TOKEN')]) {
          def secretVal = getVaultKV(
            defaults,
            env.VAULT_TOKEN,
            obj.secret)

          setParamString += """'${secretVal}'"""
        }
      } else if (obj.value) {
        setParamString += " --set ${obj.key}="
        setParamString += """'${obj.value}'"""
      } 
    }
  }

  return setParamString
}

def getScriptImages() {
  // collect script containers
  def scriptContainers = []
  
  def check = {collection, item -> 
    for (existing in collection) {
      if (existing.name == item.name) {
        return
      }
    }

    collection.add(item)
  }

  if (pipeline.beforeScript) {
    check(scriptContainers, [name: containerName(pipeline.beforeScript.image),
      image: pipeline.beforeScript.image,
      shell: pipeline.beforeScript.shell])
  }
  if (pipeline.afterScript) {
    check(scriptContainers, [name: containerName(pipeline.afterScript.image),
      image: pipeline.afterScript.image,
      shell: pipeline.afterScript.shell])
  }

  if (isPRBuild || isSelfTest) {
    if (pipeline.test.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.test.afterScript.image),
        image: pipeline.test.afterScript.image,
        shell: pipeline.test.afterScript.shell])
    }
    if (pipeline.test.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.test.beforeScript.image),
        image: pipeline.test.beforeScript.image,
        shell: pipeline.test.beforeScript.shell])
    }
    if (pipeline.stage.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.afterScript.image),
        image: pipeline.stage.afterScript.image,
        shell: pipeline.stage.afterScript.shell])
    }
    if (pipeline.stage.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.stage.beforeScript.image),
        image: pipeline.stage.beforeScript.image,
        shell: pipeline.stage.beforeScript.shell])
    }

    for (config in pipeline.deployments) {
      for (test in config.test.tests) {
        check(scriptContainers, [name: containerName(test.image),
          image: test.image,
          shell: test.shell])
      }

      for (test in config.stage.tests) {
        check(scriptContainers, [name: containerName(test.image),
          image: test.image,
          shell: test.shell])
      }
    }
  }

  if (isMasterBuild || isSelfTest) {
    if (pipeline.prod.afterScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.afterScript.image),
        image: pipeline.prod.afterScript.image,
        shell: pipeline.prod.afterScript.shell])
    }
    if (pipeline.prod.beforeScript) {
      check(scriptContainers, [name: containerName(pipeline.prod.beforeScript.image),
        image: pipeline.prod.beforeScript.image,
        shell: pipeline.prod.beforeScript.shell])
    }
  }

  for (build in pipeline.builds) {
    if (build.script || build.commands ) {
      check(scriptContainers, [name: containerName(build.image),
        image: build.image,
        shell: build.shell])
    }
  }

  return scriptContainers
}

// run any kind of user script defined with :
// ---
// image: registry.com/some-image:tag
// shell: /bin/bash
// script: path/to/some-script.sh
// ---
// yaml definition 
def executeUserScript(stageText, scriptObj, additionalEnvs = []) {
  if (scriptObj) {
    stage(stageText) {
      container(containerName(scriptObj.image)) {

        // unstash kubeconfig files
        unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

        withEnv(
          [
            "PIPELINE_PROD_NAMESPACE=${pipeline.prod.namespace}",
            "PIPELINE_STAGE_NAMESPACE=${pipeline.stage.namespace}",
            "PIPELINE_TEST_NAMESPACE=${kubeName(env.JOB_NAME)}",
            "PIPELINE_BUILD_ID=${env.BUILD_ID}",
            "PIPELINE_JOB_NAME=${env.JOB_NAME}",
            "PIPELINE_BUILD_NUMBER=${env.BUILD_NUMBER}",
            "PIPELINE_WORKSPACE=${env.WORKSPACE}"
          ] + additionalEnvs) {
          if (scriptObj.commands) {
            sh(scriptObj.commands)
          }
          if (scriptObj.script) {
            sh(readFile(scriptObj.script))
          }
        }
      }
    }
  }
}

// create certificates for prod or staging
def createCert(namespace) {

  if (!pipeline.tls) {
    return
  }

  def defaultIssuerName
  def kubeconfigStr
  switch (namespace) {
    case pipeline.stage.namespace:
      defaultIssuerName = defaults.tls.stagingIssuer
      kubeconfigStr = "--kubeconfig=${env.BUILD_ID}-staging.kubeconfig"
      break
    case pipeline.prod.namespace:
      defaultIssuerName = defaults.tls.prodIssuer
      kubeconfigStr = "--kubeconfig=${env.BUILD_ID}-prod.kubeconfig"
      break
    default:
      error("Unrecognized namespace ${namespace}")
      break
  }  

  // unstash kubeconfig files
  unstashCheck("${env.BUILD_ID}-kube-configs".replaceAll('-','_'))

  for (tlsConf in pipeline.tls[namespace]) {
    // try to cleanup previous cert first
    sh("kubectl delete Certificate ${tlsConf.name} --namespace ${namespace} ${kubeconfigStr} || true")

    // create cert object, write to file, and install into cluster
    def cert = createCertificate(tlsConf, defaultIssuerName)
    
    toYamlFile(cert, "${pwd()}/${tlsConf.name}-cert.yaml")
    sh("kubectl create -f ${pwd()}/${tlsConf.name}-cert.yaml --namespace ${namespace} ${kubeconfigStr}")
  }
}

return this
