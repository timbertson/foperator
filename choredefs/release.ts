import { run } from 'https://raw.githubusercontent.com/timbertson/chored/c25908e0a2cb90cbab26fea4bca3364cb709f82a/lib/cmd.ts#main'

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
