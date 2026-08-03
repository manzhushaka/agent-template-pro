import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from '../node_modules/typescript/lib/typescript.js'

function loadSessionFence() {
  const source = readFileSync(new URL('../src/sessionFence.ts', import.meta.url), 'utf8')
  const output = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText
  const module = { exports: {} }
  vm.runInNewContext(output, { exports: module.exports, module })
  return module.exports.SessionFence
}

const SessionFence = loadSessionFence()

test('logout invalidates an in-flight response before it can overwrite cleared state', async () => {
  const fence = new SessionFence()
  let token = 'administrator-a'
  let pageItems = []
  let resolveResponse
  const response = new Promise(resolve => {
    resolveResponse = resolve
  })
  const lease = fence.begin(token)
  const pendingWrite = response.then(items => {
    if (fence.isCurrent(lease, token)) {
      pageItems = items
    }
  })

  token = ''
  fence.invalidate()
  resolveResponse(['belonging-to-administrator-a'])
  await pendingWrite

  assert.deepEqual(pageItems, [])
})

test('a current-session 401 invalidates its lease and blocks a late success from the same session', async () => {
  const fence = new SessionFence()
  let token = 'administrator-a'
  let detail = null
  let resolveResponse
  const response = new Promise(resolve => {
    resolveResponse = resolve
  })
  const lease = fence.begin(token)
  const pendingWrite = response.then(result => {
    if (fence.isCurrent(lease, token)) {
      detail = result
    }
  })

  // This is the same state transition as requestForSession handling a 401.
  fence.invalidate()
  token = ''
  resolveResponse({ id: 'sensitive-detail' })
  await pendingWrite

  assert.equal(detail, null)
})

test('a stale 401 lease cannot invalidate a newer authenticated session', () => {
  const fence = new SessionFence()
  const oldLease = fence.begin('administrator-a')
  fence.activate()
  const currentToken = 'administrator-b'

  assert.equal(fence.isCurrent(oldLease, currentToken), false)
  assert.equal(fence.begin(currentToken)?.token, currentToken)
})

test('a stale save cannot navigate details after the refresh completed under a newer session', async () => {
  const fence = new SessionFence()
  let token = 'administrator-a'
  let selectedKnowledgeBase = null
  let resolveLoad
  const load = new Promise(resolve => {
    resolveLoad = resolve
  })
  const lease = fence.begin(token)

  // saveKnowledgeBase: the write is still current, then refresh starts.
  const saved = { id: 'knowledge-base-a', displayName: 'A' }
  assert.equal(fence.isCurrent(lease, token), true)
  const pendingRefresh = load.then(() => {
    // This mirrors the fixed chain guard: navigation only happens while current.
    if (!fence.isCurrent(lease, token)) {
      return
    }
    selectedKnowledgeBase = saved
  })

  // During the refresh the administrator logs out and B authenticates.
  token = 'administrator-b'
  fence.invalidate()
  resolveLoad()
  await pendingRefresh

  assert.equal(selectedKnowledgeBase, null)
})
