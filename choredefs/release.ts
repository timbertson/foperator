import { run } from 'https://raw.githubusercontent.com/timbertson/chored/d52293197173204bdcd60bd9bb6e2233a3e818a3/lib/cmd.ts#main'

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
