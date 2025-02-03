import { run } from 'https://raw.githubusercontent.com/timbertson/chored/a70d670bea44c243554373f115513775f3ed6231/lib/cmd.ts#main'

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
