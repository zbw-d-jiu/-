import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse } from '@babel/parser'
import compiler from 'vue-template-compiler'
// 自检解析全部 JS 和 Vue 脚本/模板，语法或模板错误立即非零退出；不冒充端到端测试。
function inspect(directory) {
  for (const file of readdirSync(directory, { withFileTypes: true })) {
    const path = resolve(directory, file.name)
    if (file.isDirectory()) inspect(path)
    else if (/\.(vue|js)$/.test(file.name)) {
      const source = readFileSync(path, 'utf8')
      if (file.name.endsWith('.vue')) {
        const component = compiler.parseComponent(source)
        parse(component.script?.content || '', { sourceType: 'module' })
        const result = compiler.compile(component.template?.content || '')
        if (result.errors.length) throw new Error(`${file.name}: ${result.errors.join('; ')}`)
      } else parse(source, { sourceType: 'module' })
      console.log(`语法检查通过：${file.name}`)
    }
  }
}
inspect(resolve('src'))
// 语法通过后核验真实业务边界；断言失败退出非零，不把它替代后端或独立浏览器验收。
await import('./check-domain.mjs')
