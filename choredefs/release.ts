import { run } from 'https://raw.githubusercontent.com/timbertson/chored/cccf7a64322c34ff3d949f5e8acf55a26e803075/lib/cmd.ts#main'

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
