// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb-test/latest/pod-pull_tiproxy_jdbc_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tiproxy") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiproxy/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tiproxy/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/TiProxy.git', 'tiproxy', "main", "", "")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tiproxy') {
                    sh label: 'tiproxy', script: '[ -f bin/tiproxy ] || make'
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                        retry(2) {
                            sh "touch ws-${BUILD_TAG}"
                            sh label: 'prepare thirdparty binary', script: """
                            chmod +x download_binary.sh
                            ./download_binary.sh --tidb=master --pd=master --tikv=master
                            cp ../tiproxy/bin/tiproxy ./bin/
                            ls -alh bin/
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiproxy --version
                            """
                        }
                    }
                }
            }
        }
        stage('JDBC Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMDS'
                        values 'make deploy-jdbc8test ARGS="-x -m fast"', 'make deploy-jdbc8test ARGS="-x -m slow"',
                            'make deploy-mybatistest ARGS="-x"', 'make deploy-jooqtest ARGS="-x"',
                            'make deploy-tidbjdbctest ARGS="-x -m tls"', 'make deploy-tidbjdbctest ARGS="-x -m unique"'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                                    container("java") {
                                        sh label: "test_cmds=${TEST_CMDS} ", script: """
                                            #!/usr/bin/env bash
                                            ${TEST_CMDS}
                                        """
                                    }
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                        }
                    }
                }
            }        
        }
    }
}
