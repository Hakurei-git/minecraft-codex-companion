import { useEffect, useRef, type ReactElement } from "react";
import type { BuildPlan } from "@mc/protocol";
import * as THREE from "three";

interface PreviewBlock {
  position: { x: number; y: number; z: number };
  blockId: string;
}

interface BuildViewportProps {
  plan?: BuildPlan | undefined;
}

const DEMO_BLOCKS: PreviewBlock[] = [];
for (let x = -4; x <= 4; x += 1) {
  for (let z = -3; z <= 3; z += 1) DEMO_BLOCKS.push({ position: { x, y: 0, z }, blockId: "minecraft:grass_block" });
}
for (let y = 1; y <= 4; y += 1) {
  for (let x = -3; x <= 3; x += 1) {
    if (x === -3 || x === 3 || y === 4) {
      DEMO_BLOCKS.push({ position: { x, y, z: -2 }, blockId: y === 4 ? "minecraft:red_terracotta" : "minecraft:oak_planks" });
      DEMO_BLOCKS.push({ position: { x, y, z: 2 }, blockId: y === 4 ? "minecraft:red_terracotta" : "minecraft:oak_planks" });
    }
  }
  for (let z = -1; z <= 1; z += 1) {
    DEMO_BLOCKS.push({ position: { x: -3, y, z }, blockId: "minecraft:oak_planks" });
    DEMO_BLOCKS.push({ position: { x: 3, y, z }, blockId: "minecraft:oak_planks" });
  }
}

const FALLBACK_COLORS = [0x668f55, 0xb28a58, 0x6b747a, 0xb44c47, 0xd8b33e, 0x4f8fa3, 0x76549d, 0xc06f91];

function colorFor(blockId: string): number {
  const id = blockId.toLowerCase();
  if (/grass|leaves|moss|lime|green/u.test(id)) return 0x668f55;
  if (/oak|spruce|wood|log|plank|brown/u.test(id)) return 0xa87c4f;
  if (/stone|cobble|andesite|gray|iron/u.test(id)) return 0x7b8180;
  if (/red|brick|nether/u.test(id)) return 0xa94642;
  if (/yellow|gold|sand/u.test(id)) return 0xd6ad3d;
  if (/water|cyan|light_blue|glass/u.test(id)) return 0x4d9ab3;
  if (/blue|lapis/u.test(id)) return 0x405fa6;
  if (/purple|magenta|amethyst/u.test(id)) return 0x895b9e;
  if (/black|deepslate|coal/u.test(id)) return 0x34393b;
  if (/white|quartz|snow/u.test(id)) return 0xd7dcda;
  let hash = 0;
  for (const char of id) hash = ((hash << 5) - hash + char.codePointAt(0)!) | 0;
  return FALLBACK_COLORS[Math.abs(hash) % FALLBACK_COLORS.length]!;
}

export function BuildViewport({ plan }: BuildViewportProps): ReactElement {
  const hostRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const source = (plan?.blocks ?? DEMO_BLOCKS).filter((block) => block.blockId !== "minecraft:air");
    const samplingStep = Math.max(1, Math.ceil(source.length / 80_000));
    const blocks = samplingStep === 1 ? source : source.filter((_block, index) => index % samplingStep === 0);

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0xcad8dd);
    scene.fog = new THREE.Fog(0xcad8dd, 40, 300);

    const bounds = new THREE.Box3();
    for (const block of blocks) bounds.expandByPoint(new THREE.Vector3(block.position.x, block.position.y, block.position.z));
    if (bounds.isEmpty()) bounds.set(new THREE.Vector3(-1, -1, -1), new THREE.Vector3(1, 1, 1));
    const center = bounds.getCenter(new THREE.Vector3());
    const size = bounds.getSize(new THREE.Vector3());
    const largest = Math.max(size.x, size.y, size.z, 6);
    const target = new THREE.Vector3(center.x, center.y, center.z);

    const camera = new THREE.PerspectiveCamera(42, 1, 0.1, Math.max(500, largest * 12));
    const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "high-performance" });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = blocks.length < 20_000;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    host.appendChild(renderer.domElement);

    scene.add(new THREE.HemisphereLight(0xf7fbff, 0x556048, 2.2));
    const sun = new THREE.DirectionalLight(0xfff1d0, 3.2);
    sun.position.set(center.x + largest, center.y + largest * 2, center.z + largest);
    sun.castShadow = blocks.length < 20_000;
    scene.add(sun);

    const geometry = new THREE.BoxGeometry(0.98, 0.98, 0.98);
    const groups = new Map<number, PreviewBlock[]>();
    for (const block of blocks) {
      const color = colorFor(block.blockId);
      const group = groups.get(color) ?? [];
      group.push(block);
      groups.set(color, group);
    }
    const materials: THREE.MeshStandardMaterial[] = [];
    const meshes: THREE.InstancedMesh[] = [];
    const matrix = new THREE.Matrix4();
    for (const [color, entries] of groups) {
      const material = new THREE.MeshStandardMaterial({ color, roughness: 0.9 });
      const mesh = new THREE.InstancedMesh(geometry, material, entries.length);
      entries.forEach((block, index) => {
        matrix.makeTranslation(block.position.x, block.position.y, block.position.z);
        mesh.setMatrixAt(index, matrix);
      });
      mesh.instanceMatrix.needsUpdate = true;
      mesh.castShadow = blocks.length < 20_000;
      mesh.receiveShadow = true;
      materials.push(material);
      meshes.push(mesh);
      scene.add(mesh);
    }

    const gridSize = Math.min(512, Math.max(20, Math.ceil(Math.max(size.x, size.z) + 12)));
    const grid = new THREE.GridHelper(gridSize, Math.min(gridSize, 128), 0x586761, 0x91a19a);
    grid.position.set(center.x, bounds.min.y - 0.51, center.z);
    scene.add(grid);

    let pointerDown = false;
    let lastX = 0;
    let lastY = 0;
    let targetRotation = -0.2;
    let targetElevation = 0.42;
    let zoom = 1;
    const baseRadius = Math.max(12, largest * 1.8);
    const onPointerDown = (event: PointerEvent) => {
      pointerDown = true;
      lastX = event.clientX;
      lastY = event.clientY;
      renderer.domElement.setPointerCapture(event.pointerId);
    };
    const onPointerMove = (event: PointerEvent) => {
      if (!pointerDown) return;
      targetRotation += (event.clientX - lastX) * 0.006;
      targetElevation = Math.max(0.12, Math.min(1.15, targetElevation + (event.clientY - lastY) * 0.004));
      lastX = event.clientX;
      lastY = event.clientY;
    };
    const onPointerUp = () => { pointerDown = false; };
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      zoom = Math.max(0.55, Math.min(3.5, zoom * Math.exp(event.deltaY * 0.001)));
    };
    renderer.domElement.addEventListener("pointerdown", onPointerDown);
    renderer.domElement.addEventListener("pointermove", onPointerMove);
    renderer.domElement.addEventListener("pointerup", onPointerUp);
    renderer.domElement.addEventListener("pointercancel", onPointerUp);
    renderer.domElement.addEventListener("wheel", onWheel, { passive: false });

    let frame = 0;
    let currentRotation = targetRotation;
    let currentElevation = targetElevation;
    let currentZoom = zoom;
    const render = () => {
      currentRotation += (targetRotation - currentRotation) * 0.08;
      currentElevation += (targetElevation - currentElevation) * 0.08;
      currentZoom += (zoom - currentZoom) * 0.1;
      const radius = baseRadius * currentZoom;
      camera.position.set(
        target.x + Math.sin(currentRotation + 0.75) * radius,
        target.y + Math.sin(currentElevation) * radius,
        target.z + Math.cos(currentRotation + 0.75) * radius,
      );
      camera.lookAt(target);
      renderer.render(scene, camera);
      frame = requestAnimationFrame(render);
    };

    const resize = () => {
      const width = Math.max(host.clientWidth, 1);
      const height = Math.max(host.clientHeight, 1);
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
    };
    const observer = new ResizeObserver(resize);
    observer.observe(host);
    resize();
    render();

    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
      renderer.domElement.removeEventListener("pointerdown", onPointerDown);
      renderer.domElement.removeEventListener("pointermove", onPointerMove);
      renderer.domElement.removeEventListener("pointerup", onPointerUp);
      renderer.domElement.removeEventListener("pointercancel", onPointerUp);
      renderer.domElement.removeEventListener("wheel", onWheel);
      renderer.dispose();
      geometry.dispose();
      materials.forEach((material) => material.dispose());
      meshes.forEach((mesh) => mesh.dispose());
      if (host.contains(renderer.domElement)) host.removeChild(renderer.domElement);
    };
  }, [plan]);

  return <div ref={hostRef} className="build-viewport" aria-label="建筑三维预览" />;
}
