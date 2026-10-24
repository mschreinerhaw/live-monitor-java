import { execFileSync } from 'node:child_process'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import assert from 'node:assert/strict'
const name = process.env.LIVE_MONITOR_TEST_MYSQL_CONTAINER
assert(name?.startsWith('live-monitor-ops-'), 'Explicit isolated acceptance container required')
const inspect = JSON.parse(execFileSync('docker', ['inspect', name], { encoding: 'utf8' }))[0]
assert.equal(inspect.Config.Labels['live-monitor.acceptance'], 'true')
assert.equal(inspect.HostConfig.NetworkMode, 'none')
const schema = ['V001__container_ops.sql', 'V002__task_events.sql'].map(file => readFileSync('live-monitor-java/src/main/resources/db/ops/' + file, 'utf8')).join('\n')
const apply = 'CREATE DATABASE IF NOT EXISTS ops_acceptance; USE ops_acceptance;\n' + schema
execFileSync('docker', ['exec', '-i', name, 'mysql', '-uroot'], { input: apply, stdio: ['pipe', 'pipe', 'pipe'] })
// Apply twice to check non-destructive migration reruns on the actual MySQL dialect.
execFileSync('docker', ['exec', '-i', name, 'mysql', '-uroot'], { input: apply, stdio: ['pipe', 'pipe', 'pipe'] })
const result = execFileSync('docker', ['exec', name, 'mysql', '-uroot', '-N', '-D', 'ops_acceptance', '-e', 'SHOW TABLES'], { encoding: 'utf8' }).trim().split(/\r?\n/)
for (const table of ['ops_resource', 'ops_task', 'ops_audit', 'ops_lock', 'ops_migration', 'ops_task_event']) assert(result.includes(table))
mkdirSync('target/e2e/mysql', { recursive: true })
writeFileSync('target/e2e/mysql/acceptance.json', JSON.stringify({ tables: result, appliedTwice: true, network: 'none', destructiveStatements: false }, null, 2))
console.log(JSON.stringify({ mysqlMigration: 'PASSED', tables: result.length, appliedTwice: true }))
