export default function App() {
  return (
    <div
      className="size-full relative overflow-hidden"
      style={{
        background: "#000",
        fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'SF Pro Display', sans-serif",
      }}
    >
      {/* Fullscreen video — rotated 90°, object-fit contain so nothing is cropped */}
      <video
        src="https://cdn.dribbble.com/userupload/31110973/file/original-c00ce340ffea6186dc2a6499c1ef59e1.mp4"
        autoPlay
        loop
        muted
        playsInline
        style={{
          position: "absolute",
          top: "50%",
          left: "50%",
          /* rotate 90° then scale to fill: swap w/h dimensions */
          transform: "translate(-50%, -50%) rotate(90deg)",
          width: "120vh",
          height: "120vw",
          objectFit: "cover",
        }}
      />

      {/* Text overlay — bottom left */}
      <div
        style={{
          position: "absolute",
          bottom: 48,
          left: 32,
        }}
      >
        <p
          className="shimmer-text"
          style={{
            fontSize: 22,
            fontWeight: 600,
            letterSpacing: "-0.3px",
            margin: 0,
            lineHeight: 1.25,
          }}
        >
          Setting up for the first time...
        </p>
        <p
          style={{
            fontSize: 10.5,
            fontWeight: 400,
            color: "rgba(255,255,255,0.45)",
            letterSpacing: "0.1px",
            margin: "5px 0 0",
            lineHeight: 1.4,
            whiteSpace: "nowrap",
          }}
        >
          Please wait, this will take a couple of seconds only
        </p>
      </div>
    </div>
  );
}
