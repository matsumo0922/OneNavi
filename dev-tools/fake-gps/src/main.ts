import { initMap } from "./map";
import { SimulationEngine } from "./simulation";
import { ControlsManager } from "./controls";
import { KeyboardController } from "./keyboard";

async function main(): Promise<void> {
  // Google Maps API を動的にロード
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY as string;
  if (!apiKey || apiKey === "your_google_api_key_here") {
    document.body.innerHTML = `
      <div style="display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;">
        <div style="text-align:center;">
          <h2>API Key Required</h2>
          <p>Create <code>.env</code> with <code>VITE_GOOGLE_API_KEY=your_key</code></p>
          <p>See <code>.env.example</code></p>
        </div>
      </div>
    `;
    return;
  }

  // Google Maps JS API のスクリプトを挿入
  await loadGoogleMapsApi(apiKey);

  await initMap();

  const engine = new SimulationEngine();
  const controls = new ControlsManager(engine);
  const keyboard = new KeyboardController(engine);

  controls.bind();
  keyboard.attach();
}

function loadGoogleMapsApi(apiKey: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.maps) {
      resolve();
      return;
    }

    const script = document.createElement("script");
    script.src = `https://maps.googleapis.com/maps/api/js?key=${apiKey}&libraries=places,geometry&v=weekly&loading=async&callback=__gmcb`;
    script.async = true;
    script.defer = true;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (window as any).__gmcb = () => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (window as any).__gmcb;
      resolve();
    };

    script.onerror = () => reject(new Error("Failed to load Google Maps API"));
    document.head.appendChild(script);
  });
}

main().catch(console.error);
