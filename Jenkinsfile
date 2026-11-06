pipeline {
  agent any
  parameters {
    booleanParam(name: 'ENABLE_DEPLOY', defaultValue: false, description: 'Explicitly enable build and deployment')
    string(name: 'TARGET_HOST', defaultValue: '', description: 'Deployment host')
    string(name: 'TARGET_USER', defaultValue: '', description: 'SSH user')
    string(name: 'PUBLIC_API_URL', defaultValue: 'http://localhost:18080', description: 'Public API base URL')
  }
  environment {
    DEPLOY_HOST = "${params.TARGET_HOST}"
    DEPLOY_USER = "${params.TARGET_USER}"
    DEPLOY_DIR = '/opt/openimage'
    COMPOSE_PROJECT_NAME = 'openimage_ai'
    NEXT_PUBLIC_API_BASE_URL = "${params.PUBLIC_API_URL}"
    RELEASE_ID = ''
  }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          def message = sh(script: 'git log -1 --format=%B', returnStdout: true).trim().toLowerCase()
          env.SKIP_CI = (!params.ENABLE_DEPLOY || message.contains('[skip ci]') || message.contains('[ci skip]') || message.contains('[skip cd]')) ? 'true' : 'false'
          if (env.SKIP_CI == 'true') {
            echo 'Build and deployment skipped by commit message.'
          }
        }
      }
    }
    stage('Prepare Release') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        script {
          if (!env.DEPLOY_HOST?.trim() || !env.DEPLOY_USER?.trim()) { error('Set TARGET_HOST and TARGET_USER before deployment') }
          env.RELEASE_ID = "${env.BUILD_NUMBER}-${(env.GIT_COMMIT ?: 'nogit').take(8)}"
        }
      }
    }
    stage('User Web Gate') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        dir('openImage-ts') {
          sh 'npm ci'
          sh 'CI=true npm run lint'
          sh 'npm run build'
        }
      }
    }
    stage('Admin Web Gate') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        dir('openImage-admin-ts') {
          sh 'npm ci'
          sh 'CI=true npm run lint'
          sh 'npm run build'
        }
      }
    }
    stage('Backend Gate') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        dir('openImage') {
          sh 'mvn -B test'
        }
      }
    }
    stage('Docker Build Gate') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        sh 'docker build -t openimage-backend-ci:${RELEASE_ID} ./openImage'
        sh 'docker build --build-arg NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL} -t openimage-user-ci:${RELEASE_ID} ./openImage-ts'
        sh 'docker build --build-arg NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL} -t openimage-admin-ci:${RELEASE_ID} ./openImage-admin-ts'
      }
    }
    stage('Package Workspace') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        sh 'git archive --format=tar.gz --output=openimage-${RELEASE_ID}.tar.gz HEAD'
      }
    }
    stage('Upload') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        sshagent(credentials: ['openimage-prod-ssh-key']) {
          sh 'ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DEPLOY_HOST} "mkdir -p ${DEPLOY_DIR}/releases"'
          sh 'scp -o StrictHostKeyChecking=no openimage-${RELEASE_ID}.tar.gz ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_DIR}/releases/openimage-${RELEASE_ID}.tar.gz'
        }
      }
    }
    stage('Deploy') {
      when { expression { env.SKIP_CI == 'false' } }
      steps {
        sshagent(credentials: ['openimage-prod-ssh-key']) {
          sh '''
            ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DEPLOY_HOST} "
              set -euo pipefail
              cd ${DEPLOY_DIR}
              mkdir -p releases/${RELEASE_ID}
              tar -xzf releases/openimage-${RELEASE_ID}.tar.gz -C releases/${RELEASE_ID}
              if [ ! -f .env ]; then cp releases/${RELEASE_ID}/.env.example .env; fi
              if [ -L current ]; then readlink current > previous-release.txt; fi
              ln -sfn releases/${RELEASE_ID} current
              cd current
              chmod +x scripts/deploy.sh scripts/smoke-test.sh
              ./scripts/deploy.sh backup
              ./scripts/deploy.sh deploy
              ./scripts/smoke-test.sh ${NEXT_PUBLIC_API_BASE_URL}
            "
          '''
        }
      }
    }
  }
  post {
    failure {
      script {
        if (env.SKIP_CI == 'false' && env.RELEASE_ID?.trim()) {
          sshagent(credentials: ['openimage-prod-ssh-key']) {
            sh 'ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DEPLOY_HOST} "cd ${DEPLOY_DIR}/current 2>/dev/null && ./scripts/deploy.sh rollback || true"'
          }
        }
      }
    }
  }
}
