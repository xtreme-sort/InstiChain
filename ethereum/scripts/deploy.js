const fs = require('node:fs')
const path = require('node:path')
const hre = require('hardhat')

async function main() {
  const [deployer] = await hre.ethers.getSigners()
  const ledger = await hre.ethers.deployContract('InstiChainLedger')
  await ledger.waitForDeployment()
  const address = await ledger.getAddress()
  const { chainId } = await hre.ethers.provider.getNetwork()

  const output = path.join(__dirname, '..', 'deployments', `${hre.network.name}.json`)
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.writeFileSync(output, JSON.stringify({
    address, chainId: Number(chainId), deployer: deployer.address, deployedAt: new Date().toISOString(),
  }, null, 2) + '\n')

  console.log(`InstiChainLedger deployed to ${address} (chain ${chainId})`)
  console.log(`Set INSTICHAIN_ETHEREUM_CONTRACT_ADDRESS=${address} in backend/.env`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
