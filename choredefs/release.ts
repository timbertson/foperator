import { run } from 'https://raw.githubusercontent.com/timbertson/chored/4adfc96a523295642e0b5b0404eadc776b59202f/lib/cmd.ts#main'

export default async function(opts: {}) {
	await scala2()
	await scala3()
}

export async function scala2(opts: {}={}) {
	await run(['sbt', 'release2'])
}

export async function scala3(opts: {}={}) {
	await run(['sbt', 'release3'])
}
