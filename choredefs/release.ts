import { run } from 'https://raw.githubusercontent.com/timbertson/chored/dbddf21a43be2d134a4f5d491004f3282b1a07a7/lib/cmd.ts#main'

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
