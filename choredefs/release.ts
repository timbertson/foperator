import { run } from 'https://raw.githubusercontent.com/timbertson/chored/d14d21c74825db0ebdb6ef25cd37e26ab65d57e3/lib/cmd.ts#main'

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
