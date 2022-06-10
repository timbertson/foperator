import { run } from 'https://raw.githubusercontent.com/timbertson/chored/c234db6eef6c16bc9076f2425bed1c56beaf42af/lib/cmd.ts#main'

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
