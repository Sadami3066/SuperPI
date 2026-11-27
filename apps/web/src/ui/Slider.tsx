import * as SliderPrimitive from "@radix-ui/react-slider";

export function Slider({
  value,
  min,
  max,
  step = 1,
  onValueChange,
  onValueCommit,
  ariaLabel,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  onValueChange: (val: number) => void;
  onValueCommit?: (val: number) => void;
  ariaLabel?: string;
}) {
  return (
    <SliderPrimitive.Root
      value={[value]}
      min={min}
      max={max}
      step={step}
      aria-label={ariaLabel}
      onValueChange={(vals) => {
        if (typeof vals[0] === "number") onValueChange(vals[0]);
      }}
      onValueCommit={(vals) => {
        if (typeof vals[0] === "number") onValueCommit?.(vals[0]);
      }}
      className="relative flex h-5 w-full touch-none items-center select-none"
    >
      <SliderPrimitive.Track
        className="relative h-1.5 w-full grow overflow-hidden rounded-full"
        style={{ background: "var(--pi-wash)" }}
      >
        <SliderPrimitive.Range
          className="absolute h-full rounded-full"
          style={{ background: "var(--pi-accent)" }}
        />
      </SliderPrimitive.Track>
      <SliderPrimitive.Thumb
        className="block h-4 w-4 rounded-full shadow-sm transition-transform hover:scale-110"
        style={{
          background: "var(--pi-canvas)",
          border: "1.5px solid var(--pi-accent)",
        }}
      />
    </SliderPrimitive.Root>
  );
}
