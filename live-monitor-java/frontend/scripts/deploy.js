const fs = require('fs')
const path = require('path')

const distDir = path.resolve(__dirname, '../dist')
const targetDir = path.resolve(__dirname, '../../src/main/resources/static')

function copyDir(src, dest) {
  if (!fs.existsSync(dest)) {
    fs.mkdirSync(dest, { recursive: true })
  }
  const entries = fs.readdirSync(src, { withFileTypes: true })
  for (const entry of entries) {
    const srcPath = path.join(src, entry.name)
    const destPath = path.join(dest, entry.name)
    if (entry.isDirectory()) {
      copyDir(srcPath, destPath)
    } else {
      fs.copyFileSync(srcPath, destPath)
    }
  }
}

if (fs.existsSync(distDir)) {
  console.log(`Copying Vue3 dist files from ${distDir} to ${targetDir}...`)
  copyDir(distDir, targetDir)
  console.log('Vue3 frontend successfully deployed to Spring Boot static resources!')
} else {
  console.error('dist directory does not exist, run npm run build first.')
}
