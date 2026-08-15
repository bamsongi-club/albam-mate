import { useRef, useState } from 'react';

// 이만큼 아래로 끌면 닫는다. 시트 손잡이를 살짝 건드린 정도로는 닫히지 않을 거리다.
export const SHEET_CLOSE_DRAG_PX = 80;

/**
 * 바텀시트를 손잡이에서 아래로 끌어 닫는 제스처.
 *
 * 끄는 동안에는 시트가 손가락을 따라 내려가고, 임계값을 넘겨 놓으면 닫는다. 못 넘기면 제자리로 돌아온다.
 * 위로 끄는 동작은 거리 0으로 눌러 시트를 끌어올리지 않는다.
 *
 * 시작 지점을 손잡이로 한정해 시트 본문 스크롤이 닫기로 오인되지 않게 한다. 포인터를 손잡이에 붙잡아 두어
 * 손가락이 손잡이를 벗어나도 같은 드래그로 이어진다.
 */
export function useSheetDragClose(onClose) {
  const [dragOffset, setDragOffset] = useState(0);
  const startYRef = useRef(null);

  const distanceFrom = (event) => Math.max(0, event.clientY - startYRef.current);

  const start = (event) => {
    startYRef.current = event.clientY;
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const move = (event) => {
    if (startYRef.current === null) return;
    setDragOffset(distanceFrom(event));
  };

  const end = (event) => {
    if (startYRef.current === null) return;
    const distance = distanceFrom(event);
    startYRef.current = null;
    setDragOffset(0);
    if (distance >= SHEET_CLOSE_DRAG_PX) onClose();
  };

  return {
    dragOffset,
    // 끄는 중에만 transform을 준다. 놓은 뒤에는 CSS transition이 제자리로 되돌린다.
    sheetStyle: dragOffset ? { transform: 'translateY(' + dragOffset + 'px)', transition: 'none' } : undefined,
    gripProps: { onPointerDown: start, onPointerMove: move, onPointerUp: end, onPointerCancel: end }
  };
}
