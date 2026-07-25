const fs = require("fs");
const path = require("path");
const sharp = require("sharp");

const ROOT = path.resolve(__dirname, "..");
const svgPath = path.join(ROOT, "..", "Leora", "app", "icon.svg");
const outDir = path.join(ROOT, "resources");

const BG_LIGHT = "#FBFAF7";

async function main() {
  const svg = fs.readFileSync(svgPath);

  // App icon: the brand mark rasterized at full res. @capacitor/assets derives
  // every launcher density (and the adaptive-icon safe zone) from this.
  await sharp(svg, { density: 1024 })
    .resize(1024, 1024)
    .png()
    .toFile(path.join(outDir, "icon.png"));

  // Splash: brand mark centered on the app's light background color.
  const mark = await sharp(svg, { density: 1024 }).resize(600, 600).png().toBuffer();
  await sharp({
    create: {
      width: 2732,
      height: 2732,
      channels: 4,
      background: BG_LIGHT,
    },
  })
    .composite([{ input: mark, gravity: "center" }])
    .png()
    .toFile(path.join(outDir, "splash.png"));

  console.log("Wrote resources/icon.png and resources/splash.png");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
