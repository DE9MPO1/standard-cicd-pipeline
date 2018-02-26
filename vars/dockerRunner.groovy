//#!Groovy
import org.wizeline.DevaultValues

def call(body) {

  def config = [:]

  if (body) {
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
  }

  echo "dockerRunner.groovy"
  print config

  // Validations
  if (!config.dockerImageName) {
    error 'You must provide a dockerImageName'
  }

  if (!config.dockerImageTag) {
    error 'You must provide a dockerImageTag'
  }

  if (!config.dockerRegistryCredentialsId) {
    error 'You must provide a dockerRegistryCredentialsId'
  }

  // Slack info
  def slackChannelName = config.slackChannelName ?: DevaultValues.defaultSlackChannelName
  def slackToken       = config.slackToken
  def muteSlack        = config.muteSlack ?: DevaultValues.defaultMuteSlack
  muteSlack = (muteSlack == 'true')

  // For service discovery only
  def dockerDaemonHost = config.dockerDaemonHost
  def dockerDaemonUrl  = config.dockerDaemonUrl  ?: DevaultValues.defaultDockerDaemonUrl
  def dockerDaemonPort = config.dockerDaemonPort ?: DevaultValues.defaultDockerDaemonPort
  def dockerDaemon

  // Image Info
  def dockerRegistryCredentialsId = config.dockerRegistryCredentialsId ?: DevaultValues.defaultDockerRegistryCredentialsId
  def dockerRegistry   = config.dockerRegistry   ?: DevaultValues.defaultDockerRegistry
  def dockerImageName  = config.dockerImageName
  def dockerImageTag   = config.dockerImageTag

  def jenkinsNode = config.jenkinsNode

  node (jenkinsNode){
    try {

      stage('RunContainer'){

        withCredentials([[$class: 'UsernamePasswordMultiBinding',
                          credentialsId: dockerRegistryCredentialsId,
                          passwordVariable: 'DOCKER_REGISTRY_PASSWORD',
                          usernameVariable: 'DOCKER_REGISTRY_USERNAME']]) {
          // Clean workspace before doing anything
          deleteDir()

          // Using a load balancer get the ip of a dockerdaemon and keep it for
          // future use.
          if (!dockerDaemonHost){
            dockerDaemonHost = sh(script: "dig +short ${dockerDaemonUrl} | head -n 1", returnStdout: true).trim()
          }
          dockerDaemon = "tcp://${dockerDaemonHost}:${dockerDaemonPort}"

          env.DOCKER_TLS_VERIFY = ""

          echo "Using remote docker daemon: ${dockerDaemon}"
          docker_bin="docker -H $dockerDaemon"

          sh "$docker_bin version"

          sh "$docker_bin login -u $DOCKER_REGISTRY_USERNAME -p $DOCKER_REGISTRY_PASSWORD devops.wize.mx:5000"

          // Call the buidler container
          exit_code = sh script: """
          set +e

          $docker_bin pull $dockerRegistry/$dockerImageName:$dockerImageTag || true
          docker_id=\$($docker_bin create $dockerRegistry/$dockerImageName:$dockerImageTag)
          $docker_bin start -ai \$docker_id || EXIT_CODE=\$? && true

          [ -n "\$EXIT_CODE" ] && exit \$EXIT_CODE;
          exit 0
          """, returnStatus: true

          // Ensure every exited container has been removed
          sh script: """
          containers=\$($docker_bin ps -a | grep Exited | awk '{print \$1}')
          [ -n "\$containers" ] && $docker_bin rm \$containers || exit 0
          """, returnStatus: true

          if (exit_code != 0 && exit_code != 3){
            echo "FAILURE"
            currentBuild.result = 'FAILURE'
            if (config.slackChannelName && !muteSlack){
              slackSend channel:"#${slackChannelName}",
                        color:'danger',
                        message:"Build (dockerRunner) of ${env.JOB_NAME} - ${env.BUILD_NUMBER} *FAILED*\n(${env.BUILD_URL})\ndockerImageName: ${dockerImageName}, dockerImageTag: ${dockerImageTag}\n*Build started by* : ${getUser()}"
            }
            error("FAILURE - Run container returned non 0 exit code")
            return 1
          }

          echo "SUCCESS"
          currentBuild.result = 'SUCCESS'
          if (config.slackChannelName && !muteSlack){
            slackSend channel:"#${slackChannelName}",
                      color:'good',
                      message:"Build (dockerRunner) of ${env.JOB_NAME} - ${env.BUILD_NUMBER} *SUCCESS*\n(${env.BUILD_URL})\ndockerImageName: ${dockerImageName}, dockerImageTag: ${dockerImageTag}\n*Build started by* : ${getUser()}"
          }
        }
      }
    } catch (err) {
      println err
      if (config.slackChannelName && !muteSlack){
        slackSend channel:"#${slackChannelName}",
                  color:'danger',
                  message:"Build (dockerRunner) of ${env.JOB_NAME} - ${env.BUILD_NUMBER} *FAILED*\n(${env.BUILD_URL})\ndockerImageName: ${dockerImageName},  dockerImageTag: ${dockerImageTag}\n*Build started by* : ${getUser()}"
      }
      throw err
    }
  }
}
